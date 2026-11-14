package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

internal class VoiceRecorder(
    private val context: Context,
    val file: File,
    private val interrupted: () -> Unit,
) {
    private var recorder: MediaRecorder? = null
    var paused = false
        private set
    private var startedAt = 0L
    private var elapsed = 0L

    fun duration(): Long = elapsed + if (paused) 0 else SystemClock.elapsedRealtime() - startedAt

    @Suppress("DEPRECATION")
    fun start() {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(16_000)
            media.setAudioEncodingBitRate(32_000)
            media.setOutputFile(file.path)
            media.setOnErrorListener { _, _, _ -> if (recorder === media) interrupted() }
            if (Build.VERSION.SDK_INT >= 29) {
                media.registerAudioRecordingCallback(context.mainExecutor, object : android.media.AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<android.media.AudioRecordingConfiguration>) {
                        if (recorder === media && !paused && media.activeRecordingConfiguration?.isClientSilenced == true) {
                            interrupted()
                        }
                    }
                })
            }
            media.prepare()
            media.start()
            recorder = media
            startedAt = SystemClock.elapsedRealtime()
        } catch (error: Exception) {
            media.release()
            file.delete()
            throw error
        }
    }

    fun togglePause() {
        val media = checkNotNull(recorder)
        if (paused) {
            media.resume()
            startedAt = SystemClock.elapsedRealtime()
        } else {
            media.pause()
            elapsed = duration()
        }
        paused = !paused
    }

    fun stop(): Boolean {
        val media = recorder ?: return false
        recorder = null
        return try {
            media.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            media.release()
        }
    }

    companion object {
        const val MAX_DURATION_MS = 10 * 60 * 1000L
    }
}
