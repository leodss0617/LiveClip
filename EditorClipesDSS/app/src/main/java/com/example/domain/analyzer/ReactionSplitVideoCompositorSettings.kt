package com.example.domain.analyzer

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings

/**
 * Composição vertical contínua: duas faixas de 1080x960 que se encostam
 * exatamente no meio do quadro 1080x1920, sem margem ou espaçamento.
 */
@UnstableApi
class ReactionSplitVideoCompositorSettings : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(1080, 1920)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        // Camada 0: fundo preenchido/desfocado da webcam.
        // Camada 1: webcam inteira ajustada sem cortes por cima do fundo.
        // Camadas 2 e 3: fundo preenchido e vídeo original inteiro no painel inferior.
        val centerY = if (inputId >= 2) -0.5f else 0.5f
        return StaticOverlaySettings.Builder()
            // Cada sequência já chega em 1080x960 após Presentation.
            // Não reduzir novamente: isso criava duas faixas de 480 px e um vão enorme.
            .setScale(1f, 1f)
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(0f, centerY)
            .setAlphaScale(1f)
            .build()
    }
}
