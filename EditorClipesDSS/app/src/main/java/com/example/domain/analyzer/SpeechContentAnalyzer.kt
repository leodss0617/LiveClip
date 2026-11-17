package com.example.domain.analyzer

import com.example.domain.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpeechAnalysisResult(
    val transcriptionText: String,
    val highlightedKeywords: List<String>,
    val detectedCategory: String,
    val selectionReason: String,
    val semanticScoreBonus: Int,
    val subtitleSegments: List<SubtitleSegment>
)

data class SubtitleSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isEmphasized: Boolean
)

/**
 * Speech analysis must only use words returned by the real local ASR engine.
 * This compatibility entry point deliberately returns no subtitles until the
 * video-to-PCM and Whisper inference pipeline is connected.
 */
class SpeechContentAnalyzer {
    suspend fun analyzeSpeechSegment(
        startMs: Long,
        endMs: Long,
        rawCategory: String,
        whisperModel: WhisperModel
    ): SpeechAnalysisResult = withContext(Dispatchers.Default) {
        SpeechAnalysisResult(
            transcriptionText = "",
            highlightedKeywords = emptyList(),
            detectedCategory = rawCategory,
            selectionReason = "Aguardando transcrição real do Whisper",
            semanticScoreBonus = 0,
            subtitleSegments = emptyList()
        )
    }
}
