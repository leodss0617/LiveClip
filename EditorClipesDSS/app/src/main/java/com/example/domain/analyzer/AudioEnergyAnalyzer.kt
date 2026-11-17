package com.example.domain.analyzer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioSignalMetrics(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val rmsLevel: Float,
    val peakLevel: Float,
    val zeroCrossingRate: Float,
    val isSilence: Boolean,
    val isSpeechLikely: Boolean,
    val isLaughterBurst: Boolean,
    val isReactionSpike: Boolean
)

data class BlockAnalysisResult(
    val blockIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val averageVolume: Float,
    val peakVolume: Float,
    val candidates: List<RawCandidateMoment>,
    val waveformPoints: List<Float>
)

data class RawCandidateMoment(
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val peakVolume: Float,
    val speechDensity: Float,
    val categorySuggested: String,
    val detectionReason: String
)

private data class WindowAccumulator(
    var sumSquares: Double = 0.0,
    var count: Int = 0,
    var peak: Float = 0f,
    var crossings: Int = 0,
    var lastSign: Int = 0
)

class AudioEnergyAnalyzer(private val context: Context) {

    /**
     * Decodes the compressed audio track through Android MediaCodec and measures
     * actual PCM samples. This works with common AAC/MP3/Opus sources instead of
     * interpreting compressed packets as if they were raw PCM.
     */
    suspend fun analyzeBlock(
        videoUri: Uri,
        blockIndex: Int,
        startMs: Long,
        endMs: Long,
        onProgress: (Float, Float) -> Unit
    ): BlockAnalysisResult = withContext(Dispatchers.Default) {
        val windowDurationMs = 2000L
        val totalWindows = ((endMs - startMs + windowDurationMs - 1) / windowDurationMs)
            .toInt().coerceAtLeast(1)
        val windows = Array(totalWindows) { WindowAccumulator() }
        val waveform = MutableList(totalWindows) { 0f }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var selectedTrack = -1
        var inputEnded = false
        var outputEnded = false
        var fedSamples = 0
        val info = MediaCodec.BufferInfo()

        try {
            extractor = MediaSourceHelper.createExtractor(context, videoUri)
                ?: throw IllegalStateException("Não foi possível abrir o áudio deste vídeo")

            var audioFormat: MediaFormat? = null
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    selectedTrack = track
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat
                ?: throw IllegalStateException("O vídeo não possui faixa de áudio")
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Formato de áudio desconhecido")

            extractor.selectTrack(selectedTrack)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var idleLoops = 0
            while (!outputEnded) {
                var madeProgress = false

                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Buffer de entrada indisponível")
                        inputBuffer.clear()
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime > endUs) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, max(endUs, startUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, max(endUs, startUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                                fedSamples++
                            }
                        }
                        madeProgress = true
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            accumulatePcm(
                                output.slice().order(ByteOrder.LITTLE_ENDIAN),
                                info.presentationTimeUs,
                                startMs,
                                endMs,
                                windowDurationMs,
                                windows
                            )
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        madeProgress = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d("AudioEnergyAnalyzer", "PCM format: ${decoder.outputFormat}")
                        madeProgress = true
                    }
                }

                if (madeProgress) idleLoops = 0 else idleLoops++
                if (idleLoops > 500) {
                    throw IllegalStateException("O decodificador de áudio não avançou")
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEnergyAnalyzer", "Falha ao decodificar áudio real: ${e.message}", e)
            throw e
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        val metrics = windows.mapIndexed { index, acc ->
            val windowStart = startMs + index * windowDurationMs
            val windowEnd = min(windowStart + windowDurationMs, endMs)
            if (acc.count == 0) {
                AudioSignalMetrics(windowStart, windowEnd, 0f, 0f, 0f, true, false, false, false)
            } else {
                val rms = sqrt(acc.sumSquares / acc.count).toFloat().coerceIn(0f, 1f)
                val silence = rms < 0.025f
                val speech = !silence && acc.crossings > 20
                val laugh = !silence && acc.crossings > 180 && acc.peak > 0.35f
                val reaction = acc.peak > 0.78f && acc.peak / max(0.01f, rms) > 2.2f
                AudioSignalMetrics(
                    windowStart, windowEnd, rms, acc.peak,
                    (acc.crossings.toFloat() / max(1, acc.count)).coerceIn(0f, 1f),
                    silence, speech, laugh, reaction
                )
            }
        }

        metrics.forEachIndexed { index, metric ->
            waveform[index] = metric.peakLevel
            onProgress((index + 1).toFloat() / totalWindows, metric.peakLevel)
        }

        val candidates = detectCandidateClusters(metrics)
        val avg = if (metrics.isEmpty()) 0f else metrics.map { it.rmsLevel }.average().toFloat()
        val peak = metrics.maxOfOrNull { it.peakLevel } ?: 0f
        BlockAnalysisResult(blockIndex, startMs, endMs, avg, peak, candidates, waveform.takeLast(60))
    }

    private fun accumulatePcm(
        pcm: ByteBuffer,
        presentationTimeUs: Long,
        startMs: Long,
        endMs: Long,
        windowDurationMs: Long,
        windows: Array<WindowAccumulator>
    ) {
        val channels = 1 // PCM is interleaved; treating each sample equally is sufficient for energy.
        val ptsMs = presentationTimeUs / 1000L
        if (ptsMs > endMs || ptsMs + 2500L < startMs) return
        val bytesPerSample = 2
        val sampleCount = pcm.remaining() / bytesPerSample
        if (sampleCount <= 0) return

        // MediaCodec decoder output for common AAC/MP3 tracks is PCM 16-bit.
        // If the platform reports float PCM, use the format-specific path upstream in a future extension.
        val accIndex = ((ptsMs - startMs).coerceAtLeast(0L) / windowDurationMs)
            .toInt().coerceIn(0, windows.lastIndex)
        val acc = windows[accIndex]
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        var previous = acc.lastSign
        while (pcm.remaining() >= 2) {
            val sample = pcm.short.toInt()
            val normalized = (abs(sample) / 32768f).coerceIn(0f, 1f)
            acc.sumSquares += normalized * normalized
            acc.peak = max(acc.peak, normalized)
            val sign = if (sample >= 0) 1 else -1
            if (previous != 0 && sign != previous) acc.crossings++
            previous = sign
            acc.count++
        }
        acc.lastSign = previous
    }

    private fun detectCandidateClusters(metrics: List<AudioSignalMetrics>): List<RawCandidateMoment> {
        val candidates = mutableListOf<RawCandidateMoment>()
        if (metrics.isEmpty()) return candidates
        var index = 0
        while (index < metrics.size) {
            val metric = metrics[index]
            if (metric.isReactionSpike || metric.isLaughterBurst || metric.peakLevel > 0.62f) {
                val from = max(0, index - 4)
                val to = min(metrics.lastIndex, index + 18)
                val slice = metrics.subList(from, to + 1)
                val duration = slice.last().windowEndMs - slice.first().windowStartMs
                if (duration >= 12_000L) {
                    val laughter = slice.any { it.isLaughterBurst }
                    val reaction = slice.any { it.isReactionSpike }
                    val speechDensity = slice.count { it.isSpeechLikely }.toFloat() / slice.size
                    val peak = slice.maxOf { it.peakLevel }
                    val category = when {
                        laughter -> "Engraçado"
                        reaction -> "Reação"
                        speechDensity > 0.55f -> "Momento forte"
                        else -> "Surpresa"
                    }
                    val reason = when (category) {
                        "Engraçado" -> "Pico de energia e padrão sonoro compatível com risada"
                        "Reação" -> "Pico súbito de intensidade no áudio"
                        "Momento forte" -> "Fala e energia sustentadas no trecho"
                        else -> "Mudança marcante na dinâmica do áudio"
                    }
                    val score = (peak * 55 + speechDensity * 25 + if (laughter || reaction) 20 else 8)
                        .toInt().coerceIn(50, 98)
                    candidates.add(
                        RawCandidateMoment(
                            slice.first().windowStartMs, slice.last().windowEndMs,
                            score, peak, speechDensity, category, reason
                        )
                    )
                    index = to + 4
                } else index++
            } else index++
        }
        return candidates.distinctBy { it.startMs / 5000L }
            .sortedByDescending { it.score }.take(8)
    }
}
