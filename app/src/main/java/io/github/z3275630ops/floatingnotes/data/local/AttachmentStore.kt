package io.github.z3275630ops.floatingnotes.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.backup.BackupCodec
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
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
    private val availableBytes: () -> Long = { queryAvailableBytes(root, context.filesDir) },
) {
    private val resolver = context.contentResolver
    private val thumbnailCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 16L).toInt().coerceIn(4 * 1024, 16 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

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
        require(BackupCodec.isSafeAttachmentId(id)) { "非法附件 ID: $id" }
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
        if (!BackupCodec.isSafeAttachmentId(id)) return@withContext null
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

    fun rootDirectory(): File = root

    suspend fun listReady(): List<LocalAttachment> = withContext(Dispatchers.IO) {
        val folders = root.listFiles() ?: return@withContext emptyList()
        folders
            .filter { it.isDirectory && BackupCodec.isSafeAttachmentId(it.name) }
            .mapNotNull { folder -> find(folder.name) }
            .sortedBy { it.id }
    }

    suspend fun verifySha256(id: String): Boolean = withContext(Dispatchers.IO) {
        val item = find(id) ?: return@withContext false
        val file = original(item)
        if (!file.isFile || file.length() != item.bytes) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        try {
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hex.equals(item.sha256, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    suspend fun stageFromBackupFile(
        id: String,
        name: String,
        mime: String,
        expectedBytes: Long,
        expectedSha256: String,
        sourceOriginalFile: File,
        stagingFolder: File,
    ): LocalAttachment = withContext(Dispatchers.IO) {
        require(BackupCodec.isSafeAttachmentId(id)) { "非法附件 ID: $id" }
        require(sourceOriginalFile.isFile) { "缺失还原附件文件: $id" }
        val actualLen = sourceOriginalFile.length()
        if (actualLen != expectedBytes) {
            throw AttachmentImportException("附件大小校验不一致: $name")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val destFolder = File(stagingFolder, id).apply {
            deleteRecursively()
            if (!mkdirs()) throw IOException("无法创建附件暂存目录")
        }
        val destOriginal = File(destFolder, "original")
        val buffer = ByteArray(64 * 1024)
        sourceOriginalFile.inputStream().use { input ->
            FileOutputStream(destOriginal).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
            destFolder.deleteRecursively()
            throw AttachmentImportException("附件哈希校验失败: $name")
        }
        // Thumbnails are rebuildable; try generating preview best-effort without failing hash-verified restore.
        val kind = AttachmentKind.fromMime(mime)
        if (kind != null) {
            runCatching { validateMedia(destOriginal, kind, mime, File(destFolder, "preview.jpg")) }
        }
        writeSynced(File(destFolder, "metadata.json")) {
            it.write(
                JSONObject().put("name", name).put("mime", mime)
                    .put("bytes", actualLen).put("sha256", actualHash)
                    .toString().toByteArray(Charsets.UTF_8),
            )
        }
        syncDirectory(destFolder)
        LocalAttachment(id, name, mime, actualLen, actualHash)
    }

    suspend fun promoteStagedAttachments(
        stagingFolder: File,
        allowOverwrite: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val stagedDirs = stagingFolder.listFiles()
            ?.filter { it.isDirectory && BackupCodec.isSafeAttachmentId(it.name) }
            .orEmpty()
        if (stagedDirs.isEmpty()) return@withContext
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Attachment directory unavailable")
        for (staged in stagedDirs) {
            val id = staged.name
            val dest = File(root, id)
            if (!allowOverwrite && find(id) != null) {
                staged.deleteRecursively()
                continue
            }
            val part = File(root, "$id.part")
            part.deleteRecursively()
            // Copy/move into .part inside root filesystem so Os.rename is atomic on same mount.
            copyDirSynced(staged, part)
            if (dest.exists()) {
                if (!allowOverwrite && find(id) != null) {
                    part.deleteRecursively()
                    staged.deleteRecursively()
                    continue
                }
                val oldBackup = File(root, "$id.old")
                oldBackup.deleteRecursively()
                Os.rename(dest.path, oldBackup.path)
                try {
                    Os.rename(part.path, dest.path)
                    oldBackup.deleteRecursively()
                    thumbnailCache.remove(id)
                } catch (err: Exception) {
                    if (!dest.exists() && oldBackup.exists()) {
                        runCatching { Os.rename(oldBackup.path, dest.path) }
                    }
                    throw err
                }
            } else {
                Os.rename(part.path, dest.path)
            }
            staged.deleteRecursively()
        }
        syncDirectory(root)
        root.parentFile?.let { syncDirectory(it) }
    }

    private fun copyDirSynced(src: File, dst: File) {
        if (!dst.mkdirs()) throw IOException("无法创建目标附件目录: ${dst.path}")
        for (child in src.listFiles().orEmpty()) {
            if (child.isFile) {
                writeSynced(File(dst, child.name)) { out ->
                    child.inputStream().use { it.copyTo(out) }
                }
            }
        }
        syncDirectory(dst)
    }

    private fun readBackupLeaseIds(): Set<String> {
        val journalDir = File(root.parentFile ?: context.filesDir, "backup_journal")
        if (!journalDir.isDirectory) return emptySet()
        val retained = HashSet<String>()
        for (name in listOf("leases.json", "pending_restore.json")) {
            val file = File(journalDir, name)
            if (!file.isFile) continue
            runCatching {
                val obj = MiniJson.parseObject(file.readText(Charsets.UTF_8))
                (obj["leased_attachment_ids"] as? List<*>)?.mapNotNull { it as? String }?.let(retained::addAll)
                (obj["staged_attachment_ids"] as? List<*>)?.mapNotNull { it as? String }?.let(retained::addAll)
            }
        }
        return retained
    }

    suspend fun thumbnail(id: String): Bitmap? {
        if (!BackupCodec.isSafeAttachmentId(id)) return null
        thumbnailCache.get(id)?.takeIf { !it.isRecycled }?.let { return it }
        return withContext(Dispatchers.IO) {
            thumbnailCache.get(id)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            val decoded = BitmapFactory.decodeFile(File(root, "$id/preview.jpg").path)
            if (decoded != null && !decoded.isRecycled) {
                thumbnailCache.put(id, decoded)
            }
            decoded
        }
    }

    fun evictThumbnail(id: String) {
        thumbnailCache.remove(id)
    }

    fun clearThumbnailCache() {
        thumbnailCache.evictAll()
    }

    suspend fun totalStorageBytes(): Long = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext 0L
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun availableSpace(): Long = withContext(Dispatchers.IO) { availableBytes() }

    // Run before the first import in this process. Existing editors cannot create a new reference
    // to an unreferenced file. Drafts, all revisions, soft-deleted notes, and active backup leases keep their media.
    suspend fun recover(database: NotesDatabase, extraRetainedIds: Set<String> = emptySet()) = recoveryLock.withLock {
        if (recovered) return@withLock
        collectUnreferenced(database, extraRetainedIds)
        recovered = true
    }

    internal suspend fun collectUnreferenced(
        database: NotesDatabase,
        extraRetainedIds: Set<String> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        val folders = root.listFiles() ?: return@withContext
        folders.filter {
            it.isDirectory && (
                (it.name.endsWith(".part") && BackupCodec.isSafeAttachmentId(it.name.removeSuffix(".part"))) ||
                    (it.name.endsWith(".old") && BackupCodec.isSafeAttachmentId(it.name.removeSuffix(".old")))
                )
        }.forEach { it.deleteRecursively() }
        val ready = folders
            .filter { it.isDirectory && BackupCodec.isSafeAttachmentId(it.name) }
            .associateBy { it.name }
            .toMutableMap()
        if (ready.isEmpty()) return@withContext
        extraRetainedIds.forEach(ready::remove)
        readBackupLeaseIds().forEach(ready::remove)
        if (ready.isEmpty()) return@withContext
        val sql = "SELECT document_json FROM notes UNION ALL SELECT document_json FROM drafts " +
            "UNION ALL SELECT document_json FROM note_versions"
        database.openHelper.readableDatabase.query(sql).use { rows ->
            while (rows.moveToNext() && ready.isNotEmpty()) {
                val document = NoteDocument.decode(rows.getString(0))
                document.blocks.forEach { block -> block.attachmentId?.let(ready::remove) }
            }
        }
        ready.forEach { (id, dir) ->
            dir.deleteRecursively()
            thumbnailCache.remove(id)
        }
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
        private val recoveryLock = Mutex()
        private var recovered = false

        private fun queryAvailableBytes(target: File, fallback: File): Long {
            var probe: File? = target
            while (probe != null && !probe.exists()) {
                probe = probe.parentFile
            }
            val resolved = probe ?: fallback
            return StatFs(resolved.path).availableBytes
        }

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
