package com.example.domain.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.domain.model.ExportResolution
import com.example.domain.model.ExportSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class ClipExportResult(
    val candidateId: Long,
    val outputFile: File,
    val subtitleFile: File?,
    val thumbnailFile: File?,
    val success: Boolean,
    val durationSeconds: Int,
    val error: String? = null
)

class VideoClipper(private val context: Context) {

    /**
     * Slices only the target time window (e.g. 00:47:31 -> 00:48:26)
     * using MediaExtractor + MediaMuxer stream copy, avoiding re-encoding 10 hours.
     */
    suspend fun cutClip(
        sourceUri: Uri,
        candidateId: Long,
        startMs: Long,
        endMs: Long,
        transcription: String,
        keywords: String,
        settings: ExportSettings,
        onProgress: (Float) -> Unit
    ): ClipExportResult = withContext(Dispatchers.IO) {
        val exportsDir = File(context.filesDir, "cortaja_exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()

        val fileName = "cortaja_${candidateId}_${startMs / 1000}_${endMs / 1000}.mp4"
        val outputFile = File(exportsDir, fileName)
        val srtFile = File(exportsDir, fileName.replace(".mp4", ".srt"))
        val thumbFile = File(exportsDir, fileName.replace(".mp4", ".jpg"))

        // Remove any stale export with the same deterministic name.
        if (outputFile.exists()) outputFile.delete()
        if (srtFile.exists()) srtFile.delete()
        if (thumbFile.exists()) thumbFile.delete()

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            onProgress(0.1f)
            // Generate synchronized subtitle file
            generateSrtSubtitle(srtFile, startMs, endMs, transcription, settings)

            // Extract thumbnail preview frame at midpoint
            generateThumbnail(sourceUri, thumbFile, (startMs + endMs) / 2)
            onProgress(0.3f)

            // Attempt fast direct stream slicing via MediaExtractor + MediaMuxer
            extractor = MediaSourceHelper.createExtractor(context, sourceUri)
                ?: throw IllegalStateException("Direct extraction not available for URI: $sourceUri")

            val trackCount = extractor.trackCount
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            val bufferSize = 1024 * 1024
            var maxBufferSize = bufferSize

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(format)
                    indexMap[i] = muxerTrack
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > maxBufferSize) maxBufferSize = size
                    }
                }
            }

            muxer.start()
            onProgress(0.5f)

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var copiedSamples = 0
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                if (sampleTimeUs >= startUs && indexMap.containsKey(trackIndex)) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    bufferInfo.presentationTimeUs = sampleTimeUs - startUs
                    bufferInfo.flags = extractor.sampleFlags
                    val muxerTrack = indexMap[trackIndex]!!
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    copiedSamples++
                }
                extractor.advance()
            }

            if (copiedSamples == 0) {
                throw IllegalStateException("Nenhum quadro de áudio/vídeo válido foi encontrado no intervalo selecionado")
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            extractor = null

            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw IllegalStateException("O arquivo exportado ficou vazio")
            }

            onProgress(1.0f)

            val durationSec = ((endMs - startMs) / 1000).toInt()
            ClipExportResult(
                candidateId = candidateId,
                outputFile = outputFile,
                subtitleFile = srtFile,
                thumbnailFile = thumbFile,
                success = true,
                durationSeconds = durationSec
            )
        } catch (e: Exception) {
            Log.e("VideoClipper", "Real video export failed: ${e.message}", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}

            // Never substitute a bundled sample video for the user's actual footage.
            // Remove incomplete artifacts so they cannot be mistaken for a valid export.
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            try { if (srtFile.exists()) srtFile.delete() } catch (_: Exception) {}
            try { if (thumbFile.exists()) thumbFile.delete() } catch (_: Exception) {}

            ClipExportResult(
                candidateId = candidateId,
                outputFile = outputFile,
                subtitleFile = null,
                thumbnailFile = null,
                success = false,
                durationSeconds = 0,
                error = "Não foi possível exportar o vídeo original: ${e.message ?: "falha de leitura ou codificação"}"
            )
        }
    }

    private fun generateThumbnail(sourceUri: Uri, outputFile: File, timeMs: Long) {
        val retriever = MediaSourceHelper.createRetriever(context, sourceUri)
        if (retriever == null) {
            generateFallbackThumbnail(outputFile, timeMs, timeMs + 30000L)
            return
        }
        try {
            val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp != null) {
                FileOutputStream(outputFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } else {
                generateFallbackThumbnail(outputFile, timeMs, timeMs + 30000L)
            }
        } catch (e: Exception) {
            Log.d("VideoClipper", "Thumbnail extraction note: ${e.message}")
            generateFallbackThumbnail(outputFile, timeMs, timeMs + 30000L)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun generateFallbackThumbnail(outputFile: File, startMs: Long, endMs: Long) {
        try {
            val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
            }
            // Draw gradient background
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, 640f,
                android.graphics.Color.rgb(18, 18, 30),
                android.graphics.Color.rgb(40, 20, 60),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, 360f, 640f, paint)

            // Draw header bar
            paint.shader = null
            paint.color = android.graphics.Color.rgb(124, 58, 237)
            canvas.drawRect(0f, 0f, 360f, 60f, paint)

            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("CORTA JÁ • CORTE 9:16", 180f, 40f, paint)

            paint.color = android.graphics.Color.rgb(245, 158, 11)
            paint.textSize = 32f
            val sec = (endMs - startMs) / 1000
            canvas.drawText("⏱ ${sec}s de Destaque", 180f, 320f, paint)

            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            Log.w("VideoClipper", "Error generating fallback thumbnail: ${e.message}")
        }
    }

    private fun generateSrtSubtitle(
        srtFile: File,
        startMs: Long,
        endMs: Long,
        transcription: String,
        settings: ExportSettings
    ) {
        val lines = transcription.split(". ").filter { it.isNotBlank() }
        val srtContent = StringBuilder()
        val durationPerLine = (endMs - startMs) / (lines.size.coerceAtLeast(1))

        lines.forEachIndexed { index, line ->
            val sStart = index * durationPerLine
            val sEnd = sStart + durationPerLine
            val startStr = formatSrtTime(sStart)
            val endStr = formatSrtTime(sEnd)

            srtContent.append("${index + 1}\n")
            srtContent.append("$startStr --> $endStr\n")
            // Style with highlighted keywords if enabled
            val formattedLine = if (settings.highlightKeywords) {
                line.replace(Regex("(?i)\\b(kkk|haha|nossa|caramba|segredo|olha|cuidado|verdade)\\b")) {
                    "<font color=\"#FFE600\"><b>${it.value.uppercase()}</b></font>"
                }
            } else line
            srtContent.append("$formattedLine\n\n")
        }

        srtFile.writeText(srtContent.toString())
    }

    private fun formatSrtTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
