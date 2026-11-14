package io.github.z3275630ops.floatingnotes.feature.capture

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

internal class SpeechException(message: String) : IOException(message)
internal data class Transcript(val raw: String, val formatted: String)

internal object OfflineTranscriber {
    suspend fun transcribe(audio: File, modelDirectory: File, onProgress: suspend (Int) -> Unit): Transcript =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(audio.path)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw SpeechException("无法读取音频轨道")
                val format = extractor.getTrackFormat(index)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                if (duration > 600_000_000L) throw SpeechException("单次转写支持 10 分钟以内的音频")
                extractor.selectTrack(index)
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val segments = mutableListOf<String>()
                Model(modelDirectory.path).use { model ->
                    val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                    try {
                        decoder.configure(format, null, null, 0)
                        decoder.start()
                        decode(extractor, decoder, model, duration, segments, onProgress)
                    } finally {
                        decoder.release()
                    }
                }
                if (segments.isEmpty()) throw SpeechException("没有识别出普通话，请检查录音后重试")
                Transcript(segments.joinToString("\n"), segments.joinToString("\n", transform = ::formatSegment))
            } finally {
                extractor.release()
            }
        }

    private suspend fun decode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        model: Model,
        duration: Long,
        segments: MutableList<String>,
        onProgress: suspend (Int) -> Unit,
    ) {
        var recognizer: Recognizer? = null
        var inputEnded = false
        var channels = 1
        var sampleRate = 0
        var progress = -1
        val info = MediaCodec.BufferInfo()
        val mono = ShortArray(2048)
        var lastOutputAt = SystemClock.elapsedRealtime()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index))
                        val count = extractor.readSampleData(input, 0)
                        inputEnded = count < 0
                        decoder.queueInputBuffer(index, 0, count.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (sampleRate != 0 && rate != sampleRate) throw SpeechException("音频的采样率在中途发生变化")
                    sampleRate = rate
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (sampleRate !in 16_000..96_000 || channels !in 1..2 ||
                        (output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                    ) throw SpeechException("此音频格式暂不支持转写")
                    if (recognizer == null) recognizer = Recognizer(model, sampleRate.toFloat())
                }
                if (index < 0) {
                    if (SystemClock.elapsedRealtime() - lastOutputAt > 10_000) {
                        throw SpeechException("音频解码超时，请检查文件后重试")
                    }
                    continue
                }
                lastOutputAt = SystemClock.elapsedRealtime()
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val speech = requireNotNull(recognizer)
                        val pcm = requireNotNull(decoder.getOutputBuffer(index))
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        val samples = pcm.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (samples.remaining() >= channels) {
                            currentCoroutineContext().ensureActive()
                            val count = minOf(mono.size, samples.remaining() / channels)
                            for (i in 0 until count) {
                                val left = samples.get().toInt()
                                mono[i] = if (channels == 1) left.toShort() else ((left + samples.get()) / 2).toShort()
                            }
                            if (speech.acceptWaveForm(mono, count)) appendResult(speech.result, segments)
                        }
                        if (info.presentationTimeUs > 600_000_000L) {
                            throw SpeechException("单次转写支持 10 分钟以内的音频")
                        }
                        if (duration > 0) {
                            val percent = (100 * info.presentationTimeUs / duration).toInt().coerceIn(0, 99)
                            if (percent > progress) {
                                onProgress(percent)
                                progress = percent
                            }
                        }
                    }
                } finally {
                    decoder.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            recognizer?.let { appendResult(it.finalResult, segments) }
        } finally {
            recognizer?.close()
        }
    }

    private fun appendResult(json: String, segments: MutableList<String>) {
        JSONObject(json).optString("text").trim().takeIf(String::isNotEmpty)?.let(segments::add)
    }

    private val wordSpace = Regex("(?<=\\p{IsHan}) +(?=\\p{IsHan})")

    private fun formatSegment(segment: String): String =
        segment.replace(wordSpace, "").let { if (it.last() in "。！？.!?") it else "$it。" }
}
