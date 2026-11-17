package com.example.domain.analyzer

import kotlin.math.max

data class NormalizedVideoRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class ReactionLayout(
    val streamerCamera: NormalizedVideoRegion,
    val reactionContent: NormalizedVideoRegion,
    val confidence: Float,
    val cameraPosition: String,
    val splitIsVertical: Boolean,
    val streamerTrackId: Int? = null
)

/**
 * Detecta a janela persistente da webcam agrupando TODOS os rostos do mesmo
 * canto. Isso evita que uma webcam com duas pessoas seja dimensionada usando
 * somente o rosto de uma delas.
 */
class ReactionLayoutDetector {
    fun detect(
        faceTrack: List<FaceTrackPoint>,
        audioEnergy: List<AudioEnergyPoint> = emptyList()
    ): ReactionLayout? {
        val candidates = faceTrack.filter {
            it.centerY in 0.015f..0.40f &&
                it.faceWidthRatio in 0.012f..0.22f &&
                it.faceHeightRatio in 0.012f..0.30f &&
                (it.centerX in 0.015f..0.40f || it.centerX in 0.60f..0.985f)
        }
        if (candidates.size < 6) return null

        data class CornerGroup(
            val points: List<FaceTrackPoint>,
            val onLeft: Boolean,
            val onTop: Boolean,
            val stability: Float,
            val score: Float
        )

        val durationUs = faceTrack.maxOfOrNull { it.timeUs } ?: return null
        val sampledTimes = faceTrack.map { it.timeUs }.distinct().size
        // A camera deve permanecer visivel em uma parte substancial das amostras.
        val minTimes = max(6, (sampledTimes * 0.40f).toInt())
        val minSpanUs = minOf(1_500_000L, (durationUs * 0.22f).toLong())

        // Cada quadrante é avaliado como uma única webcam, mesmo que contenha
        // dois ou mais rostos com trackingIds diferentes.
        val groups = candidates.groupBy { point ->
            val left = point.centerX < 0.5f
            val top = point.centerY < 0.5f
            (if (left) "L" else "R") + (if (top) "T" else "B")
        }

        val scored = groups.mapNotNull { (corner, points) ->
            val distinctTimes = points.map { it.timeUs }.distinct().size
            val spanUs = (points.maxOf { it.timeUs } - points.minOf { it.timeUs }).coerceAtLeast(0L)
            if (distinctTimes < minTimes || spanUs < minSpanUs) return@mapNotNull null

            val onLeft = corner[0] == 'L'
            val onTop = corner[1] == 'T'
            val valid = points.filter {
                (it.centerX < 0.5f) == onLeft && (it.centerY < 0.5f) == onTop
            }
            if (valid.size < 6) return@mapNotNull null

            val medianW = valid.map { it.faceWidthRatio }.sorted()[valid.size / 2]
            val medianH = valid.map { it.faceHeightRatio }.sorted()[valid.size / 2]
            val medianX = valid.map { it.centerX }.sorted()[valid.size / 2]
            val medianY = valid.map { it.centerY }.sorted()[valid.size / 2]

            // Pontuação favorece uma região que reaparece durante o vídeo,
            // com rostos pequenos e posição consistente de webcam.
            val xSpread = valid.maxOf { it.centerX } - valid.minOf { it.centerX }
            val ySpread = valid.maxOf { it.centerY } - valid.minOf { it.centerY }
            val stability = (1f - xSpread * 0.75f - ySpread * 0.75f)
                .coerceIn(0.35f, 1f)
            val sync = FaceTrackSelector.synchronizationScore(valid, audioEnergy)
            val score = distinctTimes * (0.65f + stability * 0.25f + sync * 0.10f) +
                minOf(3, valid.mapNotNull { it.trackingId }.distinct().size) * 0.15f
            CornerGroup(valid, onLeft, onTop, stability, score)
        }

        val best = scored.maxByOrNull { it.score } ?: return null
        val points = best.points
        val frameWidth = points.first().frameWidth
        val frameHeight = points.first().frameHeight
        if (frameWidth <= 0 || frameHeight <= 0 || frameWidth <= frameHeight) return null

        val xs = points.map { it.centerX }.sorted()
        val ys = points.map { it.centerY }.sorted()
        val medianFaceW = points.map { it.faceWidthRatio }.sorted()[points.size / 2]
        val medianFaceH = points.map { it.faceHeightRatio }.sorted()[points.size / 2]

        // Usa a distribuição conjunta dos rostos: com duas pessoas lado a lado,
        // a largura estimada cresce para incluir ambas, com margem externa.
        val x10 = xs[(xs.size * 0.10f).toInt().coerceIn(0, xs.lastIndex)]
        val x90 = xs[(xs.size * 0.90f).toInt().coerceIn(0, xs.lastIndex)]
        val y10 = ys[(ys.size * 0.10f).toInt().coerceIn(0, ys.lastIndex)]
        val y90 = ys[(ys.size * 0.90f).toInt().coerceIn(0, ys.lastIndex)]
        val faceClusterWidth = (x90 - x10) + medianFaceW * 3.4f
        val faceClusterHeight = (y90 - y10) + medianFaceH * 3.2f

        // A janela é ancorada na borda do quadro, como uma webcam PiP típica.
        // Os limites evitam crops gigantes quando a detecção oscila.
        val cameraWidth = max(faceClusterWidth, medianFaceH * (2.00f / (frameWidth.toFloat() / frameHeight)))
            .coerceIn(0.22f, 0.48f)
        val cameraHeight = max(faceClusterHeight, medianFaceH * 3.35f)
            .coerceIn(0.19f, 0.40f)
        val cameraLeft = if (best.onLeft) 0f else 1f - cameraWidth
        val cameraTop = if (best.onTop) 0f else 1f - cameraHeight
        val camera = NormalizedVideoRegion(
            cameraLeft.coerceAtLeast(0f),
            cameraTop.coerceAtLeast(0f),
            (cameraLeft + cameraWidth).coerceAtMost(1f),
            (cameraTop + cameraHeight).coerceAtMost(1f)
        )

        // A área inferior usa a faixa de conteúdo oposta à webcam. O renderizador
        // mantém o vídeo original completo no painel inferior para não amputar cenas.
        val safeGap = 0.008f
        val content = if (best.onLeft) {
            val edge = (camera.right + safeGap).coerceIn(0.52f, 0.98f)
            NormalizedVideoRegion(edge, 0f, 1f, 1f)
        } else {
            val edge = (camera.left - safeGap).coerceIn(0.02f, 0.48f)
            NormalizedVideoRegion(0f, 0f, edge, 1f)
        }

        val distinctTimes = points.map { it.timeUs }.distinct().size
        val confidence = (0.60f + best.stability * 0.28f +
            (distinctTimes / 100f).coerceAtMost(0.10f)).coerceIn(0f, 0.97f)
        val dominantTrack = points.mapNotNull { it.trackingId }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        return ReactionLayout(
            streamerCamera = camera,
            reactionContent = content,
            confidence = confidence,
            cameraPosition = when {
                best.onLeft && best.onTop -> "janela superior esquerda"
                !best.onLeft && best.onTop -> "janela superior direita"
                best.onLeft -> "janela inferior esquerda"
                else -> "janela inferior direita"
            },
            splitIsVertical = true,
            streamerTrackId = dominantTrack
        )
    }
}
