package com.example.domain.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Rect
import androidx.media3.effect.MatrixTransformation
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceContour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioEnergyPoint(val timeUs: Long, val rms: Float)

data class FaceTrackPoint(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val faceWidthRatio: Float = 0f,
    val faceHeightRatio: Float = 0f,
    val trackingId: Int? = null,
    val mouthOpenRatio: Float = 0f
)

/**
 * Detecta todas as faces e mede a abertura da boca por contornos labiais.
 * A análise usa amostras de 150 ms para capturar melhor o movimento da fala.
 */
class FaceTracker(private val context: Context) {
    suspend fun track(uri: Uri): List<FaceTrackPoint> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.025f)
                .enableTracking()
                .build()
        )
        // O detector de contornos é executado em cada recorte facial individual.
        // Assim conseguimos medir a boca de mais de um rosto no mesmo quadro.
        val mouthDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        val points = mutableListOf<FaceTrackPoint>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return@withContext emptyList()

            // Amostragem adaptativa: mantém precisão nos clipes curtos,
            // evitando dezenas de milhares de inferências em vídeos longos.
            val stepUs = when {
                durationMs <= 120_000L -> 150_000L
                durationMs <= 600_000L -> 300_000L
                durationMs <= 3_600_000L -> 750_000L
                else -> maxOf(1_500_000L, durationMs * 1_000L / 2_400L)
            }
            var timeUs = 0L
            while (timeUs < durationMs * 1_000L) {
                val original: Bitmap? = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (original != null) {
                    var frame: Bitmap = original
                    try {
                        val maxDimension = maxOf(original.width, original.height)
                        if (maxDimension > 960) {
                            val scale = 960f / maxDimension
                            frame = Bitmap.createScaledBitmap(
                                original,
                                (original.width * scale).toInt().coerceAtLeast(1),
                                (original.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                        }
                        val faces = Tasks.await(
                            faceDetector.process(InputImage.fromBitmap(frame, 0))
                        )
                        faces.forEach { face ->
                            val box = face.boundingBox
                            if (box.width() <= 0 || box.height() <= 0) return@forEach
                            val mouthRatio = measureMouthOpening(frame, box, mouthDetector)
                            points += FaceTrackPoint(
                                timeUs = timeUs,
                                centerX = (box.centerX().toFloat() / frame.width).coerceIn(0f, 1f),
                                centerY = (box.centerY().toFloat() / frame.height).coerceIn(0f, 1f),
                                frameWidth = original.width,
                                frameHeight = original.height,
                                faceWidthRatio = box.width().toFloat() / frame.width,
                                faceHeightRatio = box.height().toFloat() / frame.height,
                                trackingId = face.trackingId,
                                mouthOpenRatio = mouthRatio
                            )
                        }
                    } finally {
                        if (frame !== original) frame.recycle()
                        original.recycle()
                    }
                }
                timeUs += stepUs
            }
        } finally {
            try { mouthDetector.close() } catch (_: Exception) {}
            try { faceDetector.close() } catch (_: Exception) {}
            retriever.release()
        }
        points
    }

    private fun measureMouthOpening(
        frame: Bitmap,
        faceBox: Rect,
        detector: com.google.mlkit.vision.face.FaceDetector
    ): Float {
        val padX = (faceBox.width() * 0.08f).toInt()
        val padY = (faceBox.height() * 0.08f).toInt()
        val left = (faceBox.left - padX).coerceIn(0, frame.width - 1)
        val top = (faceBox.top - padY).coerceIn(0, frame.height - 1)
        val right = (faceBox.right + padX).coerceIn(left + 1, frame.width)
        val bottom = (faceBox.bottom + padY).coerceIn(top + 1, frame.height)
        val crop = try {
            Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        } catch (_: Exception) {
            return 0f
        }
        var analysisBitmap: Bitmap = crop
        return try {
            // Rostos pequenos em vídeos reagidos podem ter menos de 80 px.
            // Ampliar o recorte antes do Face Contour melhora a leitura dos lábios.
            val targetSide = 224
            val scale = (targetSide.toFloat() / maxOf(crop.width, crop.height)).coerceIn(1f, 4f)
            if (scale > 1.01f) {
                analysisBitmap = Bitmap.createScaledBitmap(
                    crop,
                    (crop.width * scale).toInt().coerceAtLeast(1),
                    (crop.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
            val detected = Tasks.await(detector.process(InputImage.fromBitmap(analysisBitmap, 0)))
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return 0f
            val upper = detected.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
            val lower = detected.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
            if (upper.isEmpty() || lower.isEmpty()) return 0f
            val upperY = upper.map { it.y }.average().toFloat()
            val lowerY = lower.map { it.y }.average().toFloat()
            ((lowerY - upperY).coerceAtLeast(0f) / analysisBitmap.height.toFloat()).coerceIn(0f, 0.20f)
        } catch (_: Exception) {
            0f
        } finally {
            if (analysisBitmap !== crop) analysisBitmap.recycle()
            crop.recycle()
        }
    }
}

/**
 * Seleciona a trilha facial cuja abertura da boca mais acompanha a energia do áudio.
 * Sem sinal de áudio/boca suficiente, usa a trilha dominante como fallback.
 */
object FaceTrackSelector {
    fun selectSpeakerTrack(
        points: List<FaceTrackPoint>,
        audioEnergy: List<AudioEnergyPoint> = emptyList(),
        excludedTrackingIds: Set<Int> = emptySet(),
        requireSync: Boolean = false
    ): List<FaceTrackPoint> {
        if (points.isEmpty()) return emptyList()
        val usable = points.filter { it.trackingId == null || it.trackingId !in excludedTrackingIds }
        if (usable.isEmpty()) return emptyList()
        val totalTimes = usable.map { it.timeUs }.distinct().size.coerceAtLeast(1)
        val tracks = usable.filter { it.trackingId != null }
            .groupBy { it.trackingId }
            .values
            .filter { it.size >= 5 }

        if (tracks.isEmpty()) {
            if (requireSync) return emptyList()
            return usable.groupBy { it.timeUs }.values.mapNotNull { frame ->
                frame.maxByOrNull { it.faceWidthRatio * it.faceHeightRatio }
            }.sortedBy { it.timeUs }
        }

        data class Candidate(val points: List<FaceTrackPoint>, val sync: Float, val score: Float)
        val candidates = tracks.map { track ->
            val coverage = track.map { it.timeUs }.distinct().size.toFloat() / totalTimes
            val areas = track.map { it.faceWidthRatio * it.faceHeightRatio }.sorted()
            val medianArea = areas[areas.size / 2]
            val sync = mouthAudioCorrelation(track, audioEnergy)
            // A sincronização boca/voz tem peso dominante; tamanho e permanência
            // servem apenas como desempate, para não escolher automaticamente o maior rosto.
            val score = sync * 100f + coverage * 15f + medianArea * 5f
            Candidate(track, sync, score)
        }
        val best = candidates.maxByOrNull { it.score } ?: return emptyList()
        if (requireSync && best.sync < 0.12f) return emptyList()
        return best.points.sortedBy { it.timeUs }
    }

    fun selectSpeakerTimeline(
        points: List<FaceTrackPoint>,
        audioEnergy: List<AudioEnergyPoint>,
        excludedTrackingIds: Set<Int> = emptySet()
    ): List<FaceTrackPoint> {
        if (points.isEmpty() || audioEnergy.isEmpty()) return emptyList()
        val windowUs = 3_000_000L
        val stepUs = 1_500_000L
        val endUs = points.maxOf { it.timeUs }
        val chosen = HashMap<Long, Pair<FaceTrackPoint, Long>>()
        var startUs = 0L
        while (startUs <= endUs) {
            val finishUs = startUs + windowUs
            val localFaces = points.filter { it.timeUs in startUs..finishUs }
            val localAudio = audioEnergy.filter { it.timeUs in startUs..finishUs }
            val selected = selectSpeakerTrack(
                localFaces, localAudio, excludedTrackingIds, requireSync = true
            )
            val centerUs = startUs + windowUs / 2L
            selected.forEach { point ->
                val distance = kotlin.math.abs(point.timeUs - centerUs)
                val previous = chosen[point.timeUs]
                if (previous == null || distance < previous.second) {
                    chosen[point.timeUs] = point to distance
                }
            }
            startUs += stepUs
        }
        return chosen.values.map { it.first }.sortedBy { it.timeUs }
    }

    fun synchronizationScore(
        points: List<FaceTrackPoint>,
        audio: List<AudioEnergyPoint>
    ): Float = mouthAudioCorrelation(points, audio)

    private fun mouthAudioCorrelation(
        track: List<FaceTrackPoint>,
        audio: List<AudioEnergyPoint>
    ): Float {
        if (audio.size < 10 || track.size < 8) return 0f
        val mouthValues = track.map { it.mouthOpenRatio.toDouble() }
        if (mouthValues.maxOrNull()!! - mouthValues.minOrNull()!! < 0.0015) return 0f
        val sortedRms = audio.map { it.rms }.sorted()
        val floor = sortedRms[(sortedRms.size * 0.20f).toInt().coerceIn(0, sortedRms.lastIndex)]
        val peak = sortedRms[(sortedRms.size * 0.90f).toInt().coerceIn(0, sortedRms.lastIndex)]
        if (peak - floor < 0.001f) return 0f

        var bestCorrelation = 0f
        for (lagMs in -250L..250L step 50L) {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (face in track) {
                val targetTime = face.timeUs + lagMs * 1_000L
                val nearest = nearestAudioSample(audio, targetTime) ?: continue
                if (kotlin.math.abs(nearest.timeUs - targetTime) > 120_000L) continue
                xs += face.mouthOpenRatio.toDouble()
                ys += ((nearest.rms - floor) / (peak - floor)).coerceIn(0f, 1f).toDouble()
            }
            if (xs.size < 8) continue
            val meanX = xs.average()
            val meanY = ys.average()
            var covariance = 0.0
            var varianceX = 0.0
            var varianceY = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                covariance += dx * dy
                varianceX += dx * dx
                varianceY += dy * dy
            }
            val denominator = sqrt(varianceX * varianceY)
            if (denominator > 1e-9) {
                val correlation = (covariance / denominator).toFloat()
                if (correlation > bestCorrelation) bestCorrelation = correlation
            }
        }
        return bestCorrelation.coerceIn(0f, 1f)
    }

    private fun nearestAudioSample(audio: List<AudioEnergyPoint>, timeUs: Long): AudioEnergyPoint? {
        var low = 0
        var high = audio.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = audio[mid].timeUs
            when {
                value < timeUs -> low = mid + 1
                value > timeUs -> high = mid - 1
                else -> return audio[mid]
            }
        }
        val before = audio.getOrNull(high)
        val after = audio.getOrNull(low)
        return when {
            before == null -> after
            after == null -> before
            kotlin.math.abs(before.timeUs - timeUs) <= kotlin.math.abs(after.timeUs - timeUs) -> before
            else -> after
        }
    }
}

/** Mantém o rosto no centro usando as coordenadas NDC exigidas pelo Media3 (-1 a 1). */
class FaceFollowTransformation(private val points: List<FaceTrackPoint>) : MatrixTransformation {
    private val zoom = 1.06f
    private val targetX = 0.50f
    private val targetY = 0.42f

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (points.isEmpty()) return Matrix()
        val time = presentationTimeUs.coerceAtLeast(0L)
        val nextIndex = points.indexOfFirst { it.timeUs >= time }
        val point = when {
            nextIndex < 0 -> points.last()
            nextIndex == 0 -> points.first()
            else -> {
                val before = points[nextIndex - 1]
                val after = points[nextIndex]
                val span = (after.timeUs - before.timeUs).coerceAtLeast(1L)
                val fraction = ((time - before.timeUs).toFloat() / span).coerceIn(0f, 1f)
                FaceTrackPoint(
                    time,
                    before.centerX + (after.centerX - before.centerX) * fraction,
                    before.centerY + (after.centerY - before.centerY) * fraction,
                    before.frameWidth,
                    before.frameHeight,
                    before.faceWidthRatio + (after.faceWidthRatio - before.faceWidthRatio) * fraction,
                    before.faceHeightRatio + (after.faceHeightRatio - before.faceHeightRatio) * fraction,
                    before.trackingId,
                    before.mouthOpenRatio + (after.mouthOpenRatio - before.mouthOpenRatio) * fraction
                )
            }
        }
        val faceNdcX = point.centerX * 2f - 1f
        val faceNdcY = 1f - point.centerY * 2f
        val targetNdcX = targetX * 2f - 1f
        val targetNdcY = 1f - targetY * 2f
        val translateX = targetNdcX - zoom * faceNdcX
        val translateY = targetNdcY - zoom * faceNdcY
        return Matrix().apply {
            setScale(zoom, zoom)
            postTranslate(translateX, translateY)
        }
    }
}