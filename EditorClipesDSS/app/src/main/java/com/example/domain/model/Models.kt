package com.example.domain.model

enum class AnalysisMode(val title: String, val description: String) {
    FAST("Modo Rápido", "Prioriza velocidade e baixo consumo. Analisa picos de volume, silêncio, fala, risadas e reações sonoras."),
    FULL("Modo Completo", "Análise de áudio + Transcrição local (Whisper). Detecta humor, histórias, surpresas, discussões e contexto.")
}

enum class WhisperModel(val modelName: String, val approxSize: String) {
    TINY("Whisper Tiny (Quantizado)", "~39 MB - Mais leve e rápido"),
    BASE("Whisper Base (Quantizado)", "~75 MB - Mais preciso para falas complexas")
}

enum class ProjectStatus {
    IDLE,
    ANALYZING,
    PAUSED,
    COMPLETED,
    ERROR
}

enum class MomentCategory(val label: String, val iconColorHex: Long) {
    ENGRAÇADO("Engraçado", 0xFFFFB703),
    REACAO("Reação", 0xFFFF5722),
    DISCUSSAO("Discussão", 0xFF9C27B0),
    HISTORIA("História", 0xFF2196F3),
    SURPRESA("Surpresa", 0xFFE91E63),
    INFORMACAO("Informação", 0xFF4CAF50),
    MOMENTO_FORTE("Momento forte", 0xFFF44336),
    OUTROS("Outros", 0xFF607D8B);

    companion object {
        fun fromLabel(label: String): MomentCategory {
            return entries.find { it.label.equals(label, ignoreCase = true) } ?: OUTROS
        }
    }
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.RES_1080x1920,
    val targetPlatform: TargetPlatform = TargetPlatform.ALL,
    val includeSubtitles: Boolean = true,
    val highlightKeywords: Boolean = true,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.TIKTOK_BOLD,
    val autoCleanTempFiles: Boolean = true
)

enum class ExportResolution(val label: String, val width: Int, val height: Int, val description: String) {
    RES_1080x1920("1080 x 1920 (Full HD 9:16)", 1080, 1920, "Qualidade máxima recomendada para TikTok e Shorts"),
    RES_720x1280("720 x 1280 (HD 9:16)", 720, 1280, "Mais leve e rápido para celulares com hardware modesto")
}

enum class TargetPlatform(val label: String) {
    ALL("TikTok / Reels / YouTube Shorts"),
    TIKTOK("TikTok (9:16)"),
    REELS("Instagram Reels (9:16)"),
    SHORTS("YouTube Shorts (9:16)")
}

enum class SubtitleStyle(val title: String) {
    TIKTOK_BOLD("TikTok Impacto (Fundo preto translúcido, texto amarelo/branco)"),
    MINIMAL_MODERN("Minimalista Moderno (Texto branco com borda suave)"),
    HIGHLIGHT_POP("Destaque Pop (Palavras-chave em amarelo neon e ciano)")
}

data class AnalysisProgress(
    val projectId: Long = 0,
    val totalDurationMs: Long = 0,
    val processedMs: Long = 0,
    val currentBlock: Int = 0,
    val totalBlocks: Int = 0,
    val candidatesCount: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val statusMessage: String = "",
    val currentPeakEnergy: Float = 0f,
    val audioWaveformSample: List<Float> = emptyList()
) {
    val remainingMs: Long
        get() = (totalDurationMs - processedMs).coerceAtLeast(0L)

    val progressFraction: Float
        get() = if (totalDurationMs > 0) (processedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
