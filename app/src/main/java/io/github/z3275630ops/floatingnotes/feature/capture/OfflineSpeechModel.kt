package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

internal class OfflineSpeechModel(context: Context) {
    private val root = File(context.noBackupFilesDir, "speech")
    private val model = File(root, NAME)

    suspend fun installed(): Boolean = withContext(Dispatchers.IO) { model.isDirectory }

    // Called by the single transcription job in this process.
    suspend fun prepare(onProgress: suspend (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        if (model.isDirectory) return@withContext model
        if (!root.isDirectory && !root.mkdirs()) throw SpeechException("无法创建模型目录")
        if (root.usableSpace < 160L * 1024 * 1024) throw SpeechException("请留出 160 MB 本机空间后重试")
        val zip = File(root, "download.part")
        val staging = File(root, "extract.part")
        staging.deleteRecursively()
        try {
            val connection = URL(URL).openConnection() as HttpsURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                if (connection.responseCode != 200) throw IOException("Model download failed")
                connection.inputStream.use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = runInterruptible { input.read(buffer) }
                            if (size < 0) break
                            total += size
                            if (total > ZIP_BYTES) throw IOException("Model download too large")
                            digest.update(buffer, 0, size)
                            output.write(buffer, 0, size)
                            val percent = (100 * total / ZIP_BYTES).toInt()
                            if (percent != lastPercent) {
                                onProgress(percent)
                                lastPercent = percent
                            }
                        }
                        if (total != ZIP_BYTES) throw IOException("Incomplete model download")
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
                throw SpeechException("语音模型校验失败，请重新下载")
            }
            onProgress(null)
            unpack(zip, staging)
            currentCoroutineContext().ensureActive()
            Os.rename(File(staging, NAME).path, model.path)
            model
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun unpack(zip: File, staging: File) {
        val boundary = staging.canonicalPath + File.separator
        val buffer = ByteArray(64 * 1024)
        var extracted = 0L
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(staging, entry.name).canonicalFile
                if (!target.path.startsWith(boundary)) throw IOException("Invalid model path")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            extracted += count
                            if (extracted > 120L * 1024 * 1024) throw IOException("Model exceeds extraction limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
            }
        }
        if (!File(staging, "$NAME/am/final.mdl").isFile) throw IOException("Incomplete model")
    }

    companion object {
        const val NAME = "vosk-model-small-cn-0.22"
        private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
        private const val ZIP_BYTES = 43_898_754L
        private const val SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    }
}
