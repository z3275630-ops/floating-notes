package io.github.z3275630ops.floatingnotes.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal enum class AttachmentKind(val blockType: String) {
    Image("image"), Audio("audio"), Video("video");

    companion object {
        fun fromMime(mime: String?) = entries.firstOrNull { it.blockType == mime?.substringBefore('/') }
    }
}

internal data class LocalAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
)

internal class AttachmentImportException(val reason: String) : IOException(reason)

internal class AttachmentStore(
    private val context: Context,
    private val root: File = File(context.filesDir, "attachments"),
    private val availableBytes: () -> Long = { root.usableSpace },
) {
    private val resolver = context.contentResolver

    suspend fun import(
        uri: Uri,
        mimeHint: String? = null,
        id: String = newId(),
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): LocalAttachment = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AttachmentImportException("无法读取来源，请重新选择")
        val declaredMime = resolver.getType(uri)
        val mime = declaredMime?.takeIf { AttachmentKind.fromMime(it) != null }
            ?: mimeHint?.takeIf { declaredMime == null || declaredMime == "application/octet-stream" }.orEmpty()
        val kind = AttachmentKind.fromMime(mime) ?: throw AttachmentImportException("请选择图片、音频或视频")
        var name = "附件"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)?.take(200)?.ifBlank { "附件" } ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        if (size == 0L) throw AttachmentImportException("文件为空")
        if ((size ?: 0) > MAX_BYTES) throw AttachmentImportException("单个附件最多 100 MB")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Attachment directory unavailable")
        requireSpace(size ?: 0L)
        require(ID.matches(id))
        find(id)?.let { return@withContext it }
        val stage = File(root, "$id.part")
        if (!stage.mkdir()) throw IOException("Cannot create staging directory")
        try {
            val media = File(stage, "original")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastUpdate = 0L
            val input = runInterruptible { resolver.openInputStream(uri) } ?: throw IOException("Source unavailable")
            input.use { stream ->
                FileOutputStream(media).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    onProgress(0, size)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = runInterruptible { stream.read(buffer) }
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_BYTES) throw AttachmentImportException("单个附件最多 100 MB")
                        requireSpace(count.toLong())
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (copied - lastUpdate >= 512 * 1024) {
                            onProgress(copied, size)
                            lastUpdate = copied
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L || (size != null && size != copied)) {
                throw AttachmentImportException("文件不完整，请重新选择")
            }
            onProgress(copied, size)
            val verifiedMime = validateMedia(media, kind, mime, File(stage, "preview.jpg"))
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            writeSynced(File(stage, "metadata.json")) {
                it.write(JSONObject().put("name", name).put("mime", verifiedMime)
                    .put("bytes", copied).put("sha256", hash).toString().toByteArray(Charsets.UTF_8))
            }
            syncDirectory(stage)
            currentCoroutineContext().ensureActive()
            Os.rename(stage.path, File(root, id).path)
            syncDirectory(root)
            syncDirectory(root.parentFile!!)
            LocalAttachment(id, name, verifiedMime, copied, hash)
        } finally {
            stage.deleteRecursively()
        }
    }

    suspend fun find(id: String): LocalAttachment? = withContext(Dispatchers.IO) {
        if (!ID.matches(id)) return@withContext null
        try {
            val metadata = JSONObject(File(root, "$id/metadata.json").readText())
            val bytes = metadata.getLong("bytes")
            val original = File(root, "$id/original")
            if (!original.isFile || original.length() != bytes) return@withContext null
            LocalAttachment(id, metadata.getString("name"), metadata.getString("mime"), bytes, metadata.getString("sha256"))
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    fun original(attachment: LocalAttachment): File = File(root, "${attachment.id}/original")

    suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!ID.matches(id)) return@withContext null
        BitmapFactory.decodeFile(File(root, "$id/preview.jpg").path)
    }

    suspend fun availableSpace(): Long = withContext(Dispatchers.IO) { context.filesDir.usableSpace }

    // Run before the first import in this process. Existing editors cannot create a new reference
    // to an unreferenced file. Drafts, all revisions, and soft-deleted notes keep their media.
    suspend fun recover(database: NotesDatabase) = recoveryLock.withLock {
        if (recovered) return@withLock
        collectUnreferenced(database)
        recovered = true
    }

    internal suspend fun collectUnreferenced(database: NotesDatabase) = withContext(Dispatchers.IO) {
        val folders = root.listFiles() ?: return@withContext
        folders.filter { it.name.endsWith(".part") && ID.matches(it.name.removeSuffix(".part")) }
            .forEach { it.deleteRecursively() }
        val ready = folders.filter { ID.matches(it.name) }.associateBy { it.name }.toMutableMap()
        if (ready.isEmpty()) return@withContext
        val sql = "SELECT document_json FROM notes UNION ALL SELECT document_json FROM drafts " +
            "UNION ALL SELECT document_json FROM note_versions"
        database.openHelper.readableDatabase.query(sql).use { rows ->
            while (rows.moveToNext() && ready.isNotEmpty()) {
                val document = NoteDocument.decode(rows.getString(0))
                document.blocks.forEach { block -> block.attachmentId?.let(ready::remove) }
            }
        }
        ready.values.forEach { it.deleteRecursively() }
    }

    private fun requireSpace(bytes: Long) {
        if (availableBytes() < bytes + MIN_FREE_BYTES) throw AttachmentImportException("本机空间不足，请腾出空间后重试")
    }

    private fun validateMedia(media: File, kind: AttachmentKind, mime: String, preview: File): String {
        val pendingPreview: Bitmap?
        val verifiedMime: String
        if (kind == AttachmentKind.Image) {
            if (Build.VERSION.SDK_INT >= 28) {
                var actualMime = mime
                pendingPreview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(media)) { decoder, info, _ ->
                    actualMime = info.mimeType
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = PREVIEW_SIZE.toFloat() / maxOf(info.size.width, info.size.height, PREVIEW_SIZE)
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                verifiedMime = actualMime
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(media.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) throw invalidMedia()
                verifiedMime = options.outMimeType ?: throw invalidMedia()
                options.inSampleSize = Integer.highestOneBit(
                    (maxOf(options.outWidth, options.outHeight) / PREVIEW_SIZE).coerceAtLeast(1),
                )
                options.inJustDecodeBounds = false
                pendingPreview = BitmapFactory.decodeFile(media.path, options) ?: throw invalidMedia()
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(media.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (duration <= 0 || (kind == AttachmentKind.Video && !hasVideo) ||
                    (kind == AttachmentKind.Audio && (!hasAudio || hasVideo))
                ) throw invalidMedia()
                verifiedMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { AttachmentKind.fromMime(it) == kind } ?: mime
                pendingPreview = if (kind == AttachmentKind.Video && Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_SIZE, PREVIEW_SIZE)
                        ?: throw invalidMedia()
                } else null
            } catch (_: RuntimeException) {
                throw invalidMedia()
            } finally {
                retriever.release()
            }
        }
        pendingPreview?.let { bitmap ->
            try {
                writeSynced(preview) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) throw IOException("Cannot encode preview")
                }
            } finally {
                bitmap.recycle()
            }
        }
        return verifiedMime
    }

    private fun invalidMedia() = AttachmentImportException("媒体已损坏或格式不受支持")

    private fun writeSynced(file: File, write: (FileOutputStream) -> Unit) {
        FileOutputStream(file).use { output ->
            write(output)
            output.fd.sync()
        }
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    companion object {
        const val MAX_BYTES = 100_000_000L
        private const val MIN_FREE_BYTES = 1024 * 1024L
        private const val PREVIEW_SIZE = 256
        private val ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val recoveryLock = Mutex()
        private var recovered = false

        fun failureMessage(error: Exception): String = when {
            error is AttachmentImportException -> error.reason
            error is SecurityException -> "来源授权已失效，请重新选择"
            generateSequence<Throwable>(error) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } ->
                "本机空间不足，请腾出空间后重试"
            Build.VERSION.SDK_INT >= 28 && error is ImageDecoder.DecodeException -> "图片已损坏或格式不受支持"
            else -> "无法导入附件。请确认文件可用后重试"
        }
    }
}
