package com.example.domain.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renderiza o vídeo inteiro em MP4 vertical. Não seleciona trechos nem altera a velocidade.
 * O áudio original permanece habilitado. O corte central é aplicado apenas ao enquadramento.
 */
@UnstableApi
class FullVideoRenderer(private val context: Context) {

    data class Result(val file: File, val durationMs: Long)

    suspend fun renderVertical(
        source: Uri,
        logo: Uri? = null,
        subtitles: List<SubtitleSegment> = emptyList(),
        faceTrack: List<FaceTrackPoint> = emptyList(),
        fontAsset: String = "fonts/BarlowCondensed-ExtraBold.ttf",
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
        val outputDir = File(context.getExternalFilesDir(null), "EditorClipesDSS")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            continuation.resumeWithException(IllegalStateException("Não foi possível criar a pasta de exportação"))
            return@suspendCancellableCoroutine
        }

        val output = File(outputDir, "CLIPESDSS_${System.currentTimeMillis()}.mp4")
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (faceTrack.isNotEmpty()) {
            // Rastreamento facial interpolado durante toda a linha do tempo.
            videoEffects += FaceFollowTransformation(faceTrack)
        }
        videoEffects += Presentation.createForWidthAndHeight(
            1080,
            1920,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )

        // Não aplicar blur no vídeo principal: blur global apagava detalhes finos
        // e não é um filtro real de redução de ruído. Preservar a imagem original.

        // A logo cadastrada pelo usuário é gravada no próprio vídeo exportado.
        if (logo != null) {
            val original = context.contentResolver.openInputStream(logo)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: throw IllegalStateException("Não foi possível abrir a logo cadastrada.")
            val maxSide = 720
            val ratio = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
            val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true
            ) else original
            val settings = StaticOverlaySettings.Builder()
                // Maior e fixada no canto superior direito, sem cobrir o centro do vídeo.
                .setBackgroundFrameAnchor(0.82f, 0.82f)
                .setOverlayFrameAnchor(0.5f, 0.5f)
                .setScale(0.46f, 0.46f)
                .setAlphaScale(0.92f)
                .build()
            videoEffects += OverlayEffect(
                listOf(BitmapOverlay.createStaticBitmapOverlay(scaled, settings))
            )
            if (scaled !== original) original.recycle()
        }

        if (subtitles.isNotEmpty()) {
            videoEffects += OverlayEffect(listOf(SubtitleTextOverlay(subtitles, context, fontAsset)))
        }

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(
                androidx.media3.transformer.Effects(
                    emptyList(),
                    videoEffects
                )
            )
            .build()

        val transformer = try {
            Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (!continuation.isActive) return
                        if (!output.exists() || output.length() <= 0L) {
                            continuation.resumeWithException(IllegalStateException("A exportação terminou, mas o arquivo ficou vazio"))
                        } else {
                            onProgress(1f)
                            continuation.resume(Result(output, result.durationMs))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        if (continuation.isActive) {
                            if (output.exists()) output.delete()
                            continuation.resumeWithException(exception)
                        }
                    }
                })
                .build()
        } catch (e: Exception) {
            continuation.resumeWithException(e)
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            try { transformer.cancel() } catch (_: Exception) {}
            try { if (output.exists()) output.delete() } catch (_: Exception) {}
        }

        try {
            onProgress(0.02f)
            transformer.start(edited, output.absolutePath)
            onProgress(0.05f)
        } catch (e: Exception) {
            try { if (output.exists()) output.delete() } catch (_: Exception) {}
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        }
    }

    suspend fun renderReactionVertical(
        source: Uri,
        layout: ReactionLayout,
        logo: Uri? = null,
        subtitles: List<SubtitleSegment> = emptyList(),
        fontAsset: String = "fonts/BarlowCondensed-ExtraBold.ttf",
        speakerTrack: List<FaceTrackPoint> = emptyList(),
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val outputDir = File(context.getExternalFilesDir(null), "EditorClipesDSS")
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                continuation.resumeWithException(IllegalStateException("Não foi possível criar a pasta de exportação"))
                return@suspendCancellableCoroutine
            }
            val output = File(outputDir, "CLIPESDSS_REACAO_" + System.currentTimeMillis() + ".mp4")

            fun cropEffect(region: NormalizedVideoRegion): Crop {
                val left = region.left * 2f - 1f
                val right = region.right * 2f - 1f
                val top = 1f - region.top * 2f
                val bottom = 1f - region.bottom * 2f
                return Crop(left, right, bottom, top)
            }

            val cameraCrop = cropEffect(layout.streamerCamera)
            // Exclui a faixa da webcam do painel inferior, evitando que o streamer
            // apareca duplicado sobre o video reagido. O audio original permanece.
            val contentCrop = cropEffect(layout.reactionContent)
            // O fundo cobre todo o painel; a webcam original fica inteira por cima,
            // com SCALE_TO_FIT para não cortar testa, ombros ou laterais da câmera.
            // Crop ja define a regiao correta; nao redimensionar antes de
            // Presentation: o Lanczos intermediario distorcia a geometria.
            // FIT mantem todo o rosto; fundo CROP preenche sem faixas vazias.
            val cameraBackgroundEffects = listOf<androidx.media3.common.Effect>(
                cameraCrop,
                Presentation.createForWidthAndHeight(1080, 960, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                GaussianBlur(0.85f)
            )
            val cameraForegroundEffects = listOf<androidx.media3.common.Effect>(
                cameraCrop,
                Presentation.createForWidthAndHeight(1080, 960, Presentation.LAYOUT_SCALE_TO_FIT)
            )
            val contentBackgroundEffects = listOf<androidx.media3.common.Effect>(
                contentCrop,
                Presentation.createForWidthAndHeight(1080, 960, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                GaussianBlur(0.85f)
            )
            val contentForegroundEffects = listOf<androidx.media3.common.Effect>(
                contentCrop,
                Presentation.createForWidthAndHeight(1080, 960, Presentation.LAYOUT_SCALE_TO_FIT)
            )

            val cameraBackgroundItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setRemoveAudio(true)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), cameraBackgroundEffects))
                .build()
            val cameraForegroundItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setRemoveAudio(true)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), cameraForegroundEffects))
                .build()
            val contentBackgroundItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setRemoveAudio(true)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), contentBackgroundEffects))
                .build()
            val contentForegroundItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(androidx.media3.transformer.Effects(emptyList(), contentForegroundEffects))
                .build()

            val cameraBackgroundSequence = EditedMediaItemSequence.withVideoFrom(listOf(cameraBackgroundItem))
            val cameraForegroundSequence = EditedMediaItemSequence.withVideoFrom(listOf(cameraForegroundItem))
            val contentBackgroundSequence = EditedMediaItemSequence.withVideoFrom(listOf(contentBackgroundItem))
            val contentSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(contentForegroundItem))

            val compositionEffects = mutableListOf<androidx.media3.common.Effect>()
            if (logo != null) {
                val original = context.contentResolver.openInputStream(logo)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("Não foi possível abrir a logo cadastrada.")
                val maxSide = 720
                val ratio = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
                val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).toInt().coerceAtLeast(1),
                    (original.height * ratio).toInt().coerceAtLeast(1),
                    true
                ) else original
                val logoSettings = StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0.82f, 0.82f)
                    .setOverlayFrameAnchor(0.5f, 0.5f)
                    .setScale(0.46f, 0.46f)
                    .setAlphaScale(0.92f)
                    .build()
                compositionEffects += OverlayEffect(
                    listOf(BitmapOverlay.createStaticBitmapOverlay(scaled, logoSettings))
                )
                if (scaled !== original) original.recycle()
            }
            if (subtitles.isNotEmpty()) {
                compositionEffects += OverlayEffect(
                    listOf(SubtitleTextOverlay(subtitles, context, fontAsset))
                )
            }

            val composition = Composition.Builder(
                cameraBackgroundSequence, cameraForegroundSequence,
                contentBackgroundSequence, contentSequence
            ).setVideoCompositorSettings(ReactionSplitVideoCompositorSettings())
                .setEffects(androidx.media3.transformer.Effects(emptyList(), compositionEffects))
                .build()

            val transformer = try {
                Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (!continuation.isActive) return
                            if (!output.exists() || output.length() <= 0L) {
                                continuation.resumeWithException(IllegalStateException("A composição terminou, mas o arquivo ficou vazio"))
                            } else {
                                onProgress(1f)
                                continuation.resume(Result(output, result.durationMs))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            if (continuation.isActive) {
                                if (output.exists()) output.delete()
                                continuation.resumeWithException(exception)
                            }
                        }
                    })
                    .build()
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                try { transformer.cancel() } catch (_: Exception) {}
                try { if (output.exists()) output.delete() } catch (_: Exception) {}
            }
            try {
                onProgress(0.02f)
                transformer.start(composition, output.absolutePath)
                onProgress(0.05f)
            } catch (e: Exception) {
                try { if (output.exists()) output.delete() } catch (_: Exception) {}
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

}
