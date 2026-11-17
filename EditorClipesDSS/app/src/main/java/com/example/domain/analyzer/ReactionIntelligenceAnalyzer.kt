package com.example.domain.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class SceneObservation(
    val startMs: Long,
    val endMs: Long,
    val labels: List<String>,
    val confidence: Float
)

data class IntelligentVideoAnalysis(
    val subtitles: List<SubtitleSegment>,
    val scenes: List<SceneObservation>,
    val eventCount: Int,
    val report: String
)

/**
 * Analisa o vídeo em pequenas cenas com ML Kit Image Labeling (modelo local),
 * relaciona o que aparece com fala, energia de áudio e movimento de boca.
 * É uma análise contextual multimodal leve, não um modelo generativo que
 * compreende qualquer situação ou intenção humana.
 */
class ReactionIntelligenceAnalyzer(private val context: Context) {
    suspend fun analyze(
        video: Uri,
        subtitles: List<SubtitleSegment>,
        faces: List<FaceTrackPoint>,
        audio: List<AudioEnergyPoint>
    ): IntelligentVideoAnalysis = withContext(Dispatchers.IO) {
        val scenes = runCatching { analyzeVisualScenes(video) }.getOrDefault(emptyList())
        val eventRows = ArrayList<String>()
        val enriched = subtitles.map { segment ->
            val aroundAudio = audio.filter { it.timeUs / 1_000L in (segment.startMs - 500)..(segment.endMs + 500) }
            val aroundFaces = faces.filter { it.timeUs / 1_000L in (segment.startMs - 500)..(segment.endMs + 500) }
            val peak = aroundAudio.maxOfOrNull { it.rms } ?: 0f
            val avg = aroundAudio.map { it.rms }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
            val mouth = aroundFaces.map { it.mouthOpenRatio }
            val mouthMean = mouth.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
            val mouthVariation = if (mouth.size >= 3) mouth.maxOrNull()!! - mouth.minOrNull()!! else 0f
            val text = segment.text.lowercase()
            val scene = scenes.minByOrNull { distanceTo(it, segment.startMs, segment.endMs) }
            val visualContext = scene?.labels.orEmpty().joinToString(" ").lowercase()
            val isGameOrSport = listOf("game", "video game", "sport", "football", "soccer", "basketball", "player").any { visualContext.contains(it) }
            val isPersonScene = visualContext.contains("person") || visualContext.contains("people")
            val laughter = Regex("(kkkk+|hahaha+|rsrs+|risada|rindo|gargalhada)").containsMatchIn(text)
            val surpriseWords = Regex("(meu deus|n[aã]o acredito|caramba|que susto|socorro|inacredit[aá]vel|n[aã]o [eé] poss[ií]vel)").containsMatchIn(text)
            val anger = Regex("(que [oó]dio|raiva|absurdo|idiota|burro)").containsMatchIn(text)
            val strongVoice = peak >= 0.58f && peak >= avg * 1.8f
            val visibleReaction = mouthMean >= 0.035f && mouthVariation >= 0.018f
            val event = when {
                laughter -> "RISADA"
                surpriseWords || (strongVoice && visibleReaction) -> "SURPRESA / REAÇÃO FORTE"
                anger -> "IRRITAÇÃO / DISCUSSÃO"
                strongVoice && isGameOrSport -> "REAÇÃO FORTE A JOGO / ESPORTE"
                strongVoice -> "PICO DE VOZ"
                visibleReaction && isPersonScene -> "PESSOA FALANDO / REAGINDO"
                visibleReaction -> "FALA / MOVIMENTO DE BOCA"
                else -> "FALA / OBSERVAÇÃO"
            }
            eventRows += "${segment.startMs / 1000}s-${segment.endMs / 1000}s | $event | visual: ${scene?.labels?.joinToString(", ").orEmpty().ifBlank { "sem rótulo confiável" }} | fala: ${segment.text}"
            val contextualText = addEventEmoji(segment.text, event)
            segment.copy(
                text = contextualText,
                isEmphasized = segment.isEmphasized || laughter || surpriseWords || anger || strongVoice
            )
        }
        val eventCount = enriched.count { it.isEmphasized }
        val report = buildString {
            appendLine("ANÁLISE INTELIGENTE DO VÍDEO")
            appendLine("Cenas visuais analisadas: ${scenes.size}")
            appendLine("Falas transcritas: ${subtitles.size}")
            appendLine("Momentos destacados: $eventCount")
            appendLine()
            appendLine("LINHA DO TEMPO DE FALA E REAÇÃO")
            eventRows.forEach { appendLine(it) }
            appendLine()
            appendLine("RÓTULOS VISUAIS POR CENA")
            scenes.forEach { scene ->
                appendLine("${scene.startMs / 1000}s-${scene.endMs / 1000}s | ${scene.labels.joinToString(", ")} | confiança ${"%.2f".format(scene.confidence)}")
            }
        }
        IntelligentVideoAnalysis(enriched, scenes, eventCount, report)
    }

    private fun analyzeVisualScenes(uri: Uri): List<SceneObservation> {
        val retriever = MediaMetadataRetriever()
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
        )
        val result = ArrayList<SceneObservation>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()
            val interval = max(1_500L, durationMs / 100L)
            var time = 0L
            while (time < durationMs) {
                val frame = retriever.getFrameAtTime(time * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    var scaled: Bitmap = frame
                    try {
                        val maxSide = 512
                        if (maxOf(frame.width, frame.height) > maxSide) {
                            val ratio = maxSide.toFloat() / maxOf(frame.width, frame.height)
                            scaled = Bitmap.createScaledBitmap(
                                frame, (frame.width * ratio).toInt().coerceAtLeast(1),
                                (frame.height * ratio).toInt().coerceAtLeast(1), true
                            )
                        }
                        val labels = Tasks.await(labeler.process(InputImage.fromBitmap(scaled, 0)))
                            .sortedByDescending { it.confidence }
                            .take(4)
                        result += SceneObservation(
                            time, (time + interval).coerceAtMost(durationMs),
                            labels.map { it.text }, labels.maxOfOrNull { it.confidence } ?: 0f
                        )
                    } catch (_: Exception) {
                        result += SceneObservation(time, (time + interval).coerceAtMost(durationMs), emptyList(), 0f)
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    }
                }
                time += interval
            }
        } finally {
            try { labeler.close() } catch (_: Exception) {}
            retriever.release()
        }
        return result
    }

    private fun distanceTo(scene: SceneObservation, start: Long, end: Long): Long {
        val middle = (start + end) / 2L
        return when {
            middle < scene.startMs -> scene.startMs - middle
            middle > scene.endMs -> middle - scene.endMs
            else -> 0L
        }
    }

    private fun addEventEmoji(raw: String, event: String): String {
        if (raw.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) return raw
        val emoji = when (event) {
            "RISADA" -> "😂"
            "SURPRESA / REAÇÃO FORTE" -> "😱"
            "IRRITAÇÃO / DISCUSSÃO" -> "😡"
            "PICO DE VOZ" -> "🔥"
            else -> ""
        }
        return if (emoji.isBlank()) raw else "$raw $emoji"
    }
}
