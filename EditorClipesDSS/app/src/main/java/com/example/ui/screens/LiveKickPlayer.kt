package com.example.ui.screens

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.json.JSONObject

internal fun playedMoment(player: Player): JSONObject? {
    val timeline = player.currentTimeline
    val index = player.currentMediaItemIndex
    if (timeline.isEmpty || index !in 0 until timeline.windowCount) return null
    val window = timeline.getWindow(index, Timeline.Window())
    if (!window.isLive) return null
    val mark = JSONObject()
    if (window.windowStartTimeMs != C.TIME_UNSET) {
        mark.put("video_ms", window.windowStartTimeMs + player.currentPosition)
    }
    if (player.duration != C.TIME_UNSET) {
        mark.put("atraso_ms", (player.duration - player.currentPosition).coerceAtLeast(0))
    }
    return mark.takeIf { it.length() > 0 }
}

@OptIn(UnstableApi::class)
@Composable
internal fun LiveVideoSurface(player: Player, modifier: Modifier, controls: Boolean = true) {
    AndroidView(
        modifier = modifier,
        factory = { PlayerView(it).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
        update = { view ->
            view.useController = controls
            view.player = player
        },
        onRelease = { it.player = null },
    )
}
