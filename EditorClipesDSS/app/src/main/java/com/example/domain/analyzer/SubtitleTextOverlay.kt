package com.example.domain.analyzer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.MetricAffectingSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay

/** Legendas dinâmicas com quebra curta e posição segura no quadro 9:16. */
class SubtitleTextOverlay(
    subtitles: List<SubtitleSegment>,
    context: Context,
    fontAsset: String
) : TextOverlay() {
    private val captionTypeface: Typeface = try {
        Typeface.createFromAsset(context.assets, fontAsset)
    } catch (_: Exception) {
        Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private data class Caption(
        val startMs: Long,
        val endMs: Long,
        val wordBoundaries: List<Long>,
        val frames: List<SpannableString>
    )

    private val captions: List<Caption> = subtitles
        .filter { it.text.isNotBlank() && it.endMs > it.startMs }
        .sortedBy { it.startMs }
        .flatMap { segment ->
            val chunks = splitIntoSafeChunks(addContextEmoji(segment.text), 13)
            val duration = segment.endMs - segment.startMs
            chunks.mapIndexedNotNull { chunkIndex, chunk ->
                val start = segment.startMs + duration * chunkIndex / chunks.size
                val end = segment.startMs + duration * (chunkIndex + 1) / chunks.size
                if (end <= start) return@mapIndexedNotNull null
                val words = chunk.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isEmpty()) return@mapIndexedNotNull null
                val frames = words.indices.map { active -> styleCaption(chunk, segment.isEmphasized, words, active) }
                val boundaries = (0..words.size).map { i -> start + (end - start) * i / words.size }
                Caption(start, end, boundaries, frames)
            }
        }

    private val emptyCaption = SpannableString(" ")
    private val settings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 0.42f)
        .setOverlayFrameAnchor(0f, 0f)
        .setScale(0.46f, 0.46f)
        .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1_000L
        val caption = findCaption(timeMs) ?: return emptyCaption
        var low = 0
        var high = caption.frames.lastIndex
        var activeWord = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (timeMs < caption.wordBoundaries[mid]) high = mid - 1
            else if (timeMs >= caption.wordBoundaries[mid + 1]) low = mid + 1
            else { activeWord = mid; break }
        }
        return caption.frames[activeWord]
    }

    private fun findCaption(timeMs: Long): Caption? {
        var low = 0
        var high = captions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = captions[mid]
            when {
                timeMs < c.startMs -> high = mid - 1
                timeMs >= c.endMs -> low = mid + 1
                else -> return c
            }
        }
        return null
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1_000L
        val caption = findCaption(timeMs) ?: return settings
        val elapsed = (timeMs - caption.startMs).coerceAtLeast(0L)
        val remaining = (caption.endMs - timeMs).coerceAtLeast(0L)
        val entrance = (elapsed / 140f).coerceIn(0f, 1f)
        val pulse = if (elapsed < 220L) 1.035f else 1f
        val scale = 0.46f * (0.94f + 0.06f * entrance) * pulse
        val fade = (remaining / 90f).coerceIn(0f, 1f)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.42f)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(scale, scale)
            .setAlphaScale(fade)
            .build()
    }

    private fun addContextEmoji(raw: String): String {
        // A analise de eventos ja pode ter acrescentado um emoji: nao duplicar.
        if (raw.codePoints().anyMatch { cp ->
                cp >= 0x1F000 || cp in 0x2600..0x27BF
            }) return raw
        val t = raw.lowercase()
        val emoji = when {
            Regex("(kkkk+|hahaha+|rsrs+|risada|rindo|gargalhada|muito engraçado)").containsMatchIn(t) -> "😂"
            Regex("(meu deus|n[aã]o acredito|inacredit[aá]vel|que absurdo|s[eé]rio isso|caramba)").containsMatchIn(t) -> "😳"
            Regex("(que susto|socorro|gritou|grito|medo|assustado|assustador)").containsMatchIn(t) -> "😱"
            Regex("(perfeito|brabo|sensacional|incr[ií]vel|mandou bem|boa demais)").containsMatchIn(t) -> "🔥"
            Regex("(olha isso|repara|presta aten[cç][aã]o|t[aá] vendo)").containsMatchIn(t) -> "👀"
            Regex("(n[aã]o pode ser|n[aã]o [eé] poss[ií]vel|que loucura)").containsMatchIn(t) -> "🤯"
            Regex("(triste|chorar|chorei|pena|infelizmente)").containsMatchIn(t) -> "🥺"
            Regex("(raiva|irritado|que [oó]dio|absurdo demais)").containsMatchIn(t) -> "😡"
            else -> ""
        }
        return if (emoji.isEmpty() || raw.contains(emoji)) raw else "$raw $emoji"
    }

    private fun styleCaption(raw: String, emphasized: Boolean, words: List<String>, activeWord: Int): SpannableString {
        val text = raw.trim()
        val styled = SpannableString(text)
        if (text.isEmpty()) return styled
        styled.setSpan(EmbeddedTypefaceSpan(captionTypeface), 0, text.length, 0)
        applySystemEmojiTypeface(styled, text)
        styled.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, 0)
        styled.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, 0)
        styled.setSpan(ReadableShadowSpan(), 0, text.length, 0)
        if (emphasized) styled.setSpan(ForegroundColorSpan(Color.rgb(255, 220, 70)), 0, text.length, 0)
        var cursor = 0
        words.forEachIndexed { index, word ->
            val found = text.indexOf(word, cursor)
            if (found >= 0) {
                val end = found + word.length
                if (index == activeWord) {
                    styled.setSpan(ForegroundColorSpan(Color.rgb(255, 218, 55)), found, end, 0)
                    styled.setSpan(RelativeSizeSpan(1.13f), found, end, 0)
                    styled.setSpan(StyleSpan(Typeface.BOLD), found, end, 0)
                }
                cursor = end
            }
        }
        return styled
    }

    private fun applySystemEmojiTypeface(styled: SpannableString, text: String) {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val count = Character.charCount(cp)
            if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0xFE00..0xFE0F ||
                cp in 0x1F1E6..0x1F1FF || cp == 0x200D || cp == 0x20E3) {
                styled.setSpan(TypefaceSpan("sans-serif"), index, index + count, 0)
            }
            index += count
        }
    }

    private fun splitIntoSafeChunks(raw: String, maxCharsPerLine: Int): List<String> {
        val tokens = raw.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotBlank() }
            .flatMap { if (it.length > maxCharsPerLine) it.chunked(maxCharsPerLine) else listOf(it) }
        val result = ArrayList<String>()
        var first = StringBuilder()
        var second = StringBuilder()
        fun flush() {
            val value = listOf(first.toString(), second.toString()).filter { it.isNotBlank() }.joinToString(" ")
            if (value.isNotBlank()) result += value
            first = StringBuilder(); second = StringBuilder()
        }
        for (word in tokens) {
            if (first.isEmpty() || first.length + 1 + word.length <= maxCharsPerLine) {
                if (first.isNotEmpty()) first.append(' ')
                first.append(word)
            } else if (second.isEmpty() || second.length + 1 + word.length <= maxCharsPerLine) {
                if (second.isNotEmpty()) second.append(' ')
                second.append(word)
            } else {
                flush()
                first.append(word)
            }
        }
        flush()
        return result.ifEmpty { listOf(" ") }
    }
}

private class EmbeddedTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) { textPaint.typeface = typeface }
    override fun updateMeasureState(textPaint: TextPaint) { textPaint.typeface = typeface }
}

private class ReadableShadowSpan : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) { textPaint.setShadowLayer(5f, 0f, 1f, Color.BLACK) }
    override fun updateMeasureState(textPaint: TextPaint) { textPaint.setShadowLayer(5f, 0f, 1f, Color.BLACK) }
}
