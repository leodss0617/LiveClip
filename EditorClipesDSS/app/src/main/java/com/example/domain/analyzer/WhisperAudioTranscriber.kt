package com.example.domain.analyzer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the video's real audio with Android MediaCodec and sends 30-second,
 * mono 16 kHz chunks to the local Whisper.cpp JNI engine.
 */
class WhisperAudioTranscriber(private val context: Context) {
    companion object {
        private const val TARGET_RATE = 16_000
        private const val CHUNK_SAMPLES = TARGET_RATE * 30
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    suspend fun transcribe(
        videoUri: Uri,
        modelFile: String,
        onProgress: (Float) -> Unit = {},
        onAudioEnergy: (AudioEnergyPoint) -> Unit = {}
    ): List<SubtitleSegment> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val bridge = WhisperNativeBridge()
        var nativeContext = 0L

        try {
            extractor.setDataSource(context, videoUri, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = candidate
                    break
                }
            }
            check(audioTrack >= 0 && inputFormat != null) {
                "Este vídeo não contém uma faixa de áudio."
            }

            extractor.selectTrack(audioTrack)
            val format = inputFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Formato de áudio não reconhecido.")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            nativeContext = bridge.initContext(modelFile)
            check(nativeContext != 0L) {
                "O Whisper não conseguiu abrir o modelo. Confira se o arquivo ggml-base.bin está completo."
            }

            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000L
            } else 0L

            val info = MediaCodec.BufferInfo()
            val pending = FloatAccumulator(CHUNK_SAMPLES + 8_192)
            val subtitles = ArrayList<SubtitleSegment>()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var chunkOffsetMs = 0L

            fun recognizeChunk(samples: FloatArray) {
                if (samples.isEmpty()) return
                val raw = bridge.transcribe(nativeContext, samples, "pt")
                    ?: error("O Whisper falhou ao reconhecer um trecho do áudio.")
                for (entry in raw) {
                    val parts = entry.split("|", limit = 3)
                    if (parts.size != 3) continue
                    val start = parts[0].toLongOrNull() ?: continue
                    val end = parts[1].toLongOrNull() ?: continue
                    val spokenText = parts[2].trim()
                    if (spokenText.isNotEmpty() && end > start) {
                        subtitles += SubtitleSegment(
                            startMs = chunkOffsetMs + start,
                            endMs = chunkOffsetMs + end,
                            text = spokenText,
                            isEmphasized = false
                        )
                    }
                }
                chunkOffsetMs += samples.size * 1_000L / TARGET_RATE
            }

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Buffer de entrada de áudio indisponível.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val presentationTime = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inputIndex, 0, size, presentationTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decodedFormat = decoder.outputFormat
                        if (decodedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (decodedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("Buffer de áudio decodificado indisponível.")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val mono = decodeToMono(outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding)
                            emitAudioEnergy(mono, sampleRate, info.presentationTimeUs.coerceAtLeast(0L), onAudioEnergy)
                            val resampled = resample(mono, sampleRate)
                            pending.append(resampled)

                            while (pending.size >= CHUNK_SAMPLES) {
                                recognizeChunk(pending.take(CHUNK_SAMPLES))
                            }

                            if (durationMs > 0L && info.presentationTimeUs > 0L) {
                                val progress = (info.presentationTimeUs / 1_000.0 / durationMs)
                                    .toFloat().coerceIn(0f, 0.98f)
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }

                        val reachedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (reachedEnd) outputEnded = true
                    }
                }
            }

            if (pending.size > 0) {
                recognizeChunk(pending.take(pending.size))
            }
            subtitles.sortedBy { it.startMs }
        } finally {
            if (nativeContext != 0L) {
                try { bridge.freeContext(nativeContext) } catch (_: Exception) {}
            }
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun emitAudioEnergy(
        samples: FloatArray,
        sampleRate: Int,
        startTimeUs: Long,
        callback: (AudioEnergyPoint) -> Unit
    ) {
        if (samples.isEmpty() || sampleRate <= 0) return
        val windowSamples = (sampleRate / 50).coerceAtLeast(1) // janelas de 20 ms
        var offset = 0
        while (offset < samples.size) {
            val end = min(samples.size, offset + windowSamples)
            var sumSquares = 0.0
            for (i in offset until end) {
                val value = samples[i].toDouble()
                sumSquares += value * value
            }
            val rms = kotlin.math.sqrt(sumSquares / (end - offset)).toFloat()
            val timeUs = startTimeUs + offset.toLong() * 1_000_000L / sampleRate
            callback(AudioEnergyPoint(timeUs, rms))
            offset = end
        }
    }

    private fun decodeToMono(buffer: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        require(channels > 0) { "Quantidade de canais de áudio inválida." }
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> error("O decodificador retornou um formato PCM não compatível.")
        }
        val frameSize = channels * bytesPerSample
        val frameCount = buffer.remaining() / frameSize
        val mono = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0f
            repeat(channels) {
                sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    buffer.float.coerceIn(-1f, 1f)
                } else {
                    (buffer.short / 32768f).coerceIn(-1f, 1f)
                }
            }
            mono[frame] = sum / channels
        }
        return mono
    }

    private fun resample(input: FloatArray, sourceRate: Int): FloatArray {
        if (input.isEmpty()) return input
        require(sourceRate > 0) { "Taxa de amostragem de áudio inválida." }
        if (sourceRate == TARGET_RATE) return input

        val outputCount = max(1, floor(input.size.toDouble() * TARGET_RATE / sourceRate).toInt())
        val output = FloatArray(outputCount)
        val ratio = sourceRate.toDouble() / TARGET_RATE
        for (i in output.indices) {
            val sourcePosition = i * ratio
            val left = min(input.lastIndex, sourcePosition.toInt())
            val right = min(input.lastIndex, left + 1)
            val fraction = (sourcePosition - left).toFloat()
            output[i] = input[left] * (1f - fraction) + input[right] * fraction
        }
        return output
    }

    private class FloatAccumulator(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(source: FloatArray) {
            if (source.isEmpty()) return
            ensureCapacity(size + source.size)
            System.arraycopy(source, 0, values, size, source.size)
            size += source.size
        }

        fun take(count: Int): FloatArray {
            require(count in 1..size)
            val result = values.copyOfRange(0, count)
            val remaining = size - count
            if (remaining > 0) System.arraycopy(values, count, values, 0, remaining)
            size = remaining
            return result
        }

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var capacity = values.size
            while (capacity < required) capacity = max(capacity * 2, required)
            values = values.copyOf(capacity)
        }
    }
}