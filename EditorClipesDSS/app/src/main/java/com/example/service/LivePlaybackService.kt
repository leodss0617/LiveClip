package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.example.MainActivity
import com.example.ui.screens.request
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class LivePlaybackUi(
    val channel: String? = null,
    val player: Player? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val muted: Boolean = false,
)

internal object LivePlayback {
    const val SHOW = "com.example.editorclips.LIVE_SHOW"
    internal const val PLAY = "com.example.editorclips.LIVE_PLAY"
    internal const val STOP = "com.example.editorclips.LIVE_STOP"
    internal const val MUTE = "com.example.editorclips.LIVE_MUTE"
    internal const val FOCUS = "com.example.editorclips.LIVE_FOCUS"
    const val LIMIT = 4
    internal val mutablePlayers = MutableStateFlow<Map<String, LivePlaybackUi>>(emptyMap())
    val players = mutablePlayers.asStateFlow()
    internal val mutable = MutableStateFlow(LivePlaybackUi())
    val state = mutable.asStateFlow()

    fun channel(raw: String): String? {
        val link = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val name = link.path?.trim('/') ?: return null
        if (!link.scheme.equals("https", ignoreCase = true) ||
            link.host?.lowercase() !in listOf("kick.com", "www.kick.com") ||
            link.userInfo != null || link.port !in listOf(-1, 443) ||
            !name.matches(Regex("[A-Za-z0-9_-]{1,40}")) ||
            name.lowercase() in setOf("browse", "categories", "search", "following", "clips", "videos")
        ) return null
        return "https://kick.com/" + name.lowercase()
    }

    fun play(context: Context, channel: String, token: String) {
        ContextCompat.startForegroundService(context,
            Intent(context, LivePlaybackService::class.java).setAction(PLAY)
                .putExtra("channel", channel).putExtra("token", token))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LivePlaybackService::class.java))
    }

    fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context, 421, Intent(context, LivePlaybackService::class.java).setAction(STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun stop(context: Context, channel: String) = control(context, STOP, channel)
    fun mute(context: Context, channel: String) = control(context, MUTE, channel)
    fun focus(context: Context, channel: String, expanded: Boolean) =
        control(context, FOCUS, channel, expanded)

    private fun control(context: Context, action: String, channel: String, expanded: Boolean = false) {
        context.startService(Intent(context, LivePlaybackService::class.java).setAction(action)
            .putExtra("channel", channel).putExtra("expanded", expanded))
    }
}

@OptIn(UnstableApi::class)
class LivePlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private class Session(val channel: String, val token: String, var muted: Boolean) {
        var player: ExoPlayer? = null
        var resolver: Job? = null
        var retries = 0
        var loading = true
        var message: String? = null
        var expanded = false
    }

    private val sessions = linkedMapOf<String, Session>()
    private var focused: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = intent?.getStringExtra("channel")?.let(LivePlayback::channel)
        val session = channel?.let(sessions::get)
        when (intent?.action) {
            LivePlayback.PLAY -> {
                val token = intent.getStringExtra("token").orEmpty()
                if (channel != null && token.isNotBlank() &&
                    (session != null || sessions.size < LivePlayback.LIMIT)
                ) {
                    val next = Session(channel, token, session?.muted ?: sessions.isNotEmpty())
                    next.expanded = session?.expanded ?: false
                    session?.let(::release)
                    sessions[channel] = next
                    if (focused == null) focused = channel
                    foreground()
                    load(next, 0)
                } else if (sessions.isEmpty()) stopSelf()
            }
            LivePlayback.STOP -> {
                if (channel == null) stopSelf()
                else {
                    sessions.remove(channel)?.let(::release)
                    if (focused == channel) focused = sessions.keys.firstOrNull()
                    publish()
                    if (sessions.isEmpty()) stopSelf() else foreground()
                }
            }
            LivePlayback.MUTE -> if (session != null) {
                session.muted = !session.muted
                session.player?.volume = if (session.muted) 0f else 1f
                publish()
            }
            LivePlayback.FOCUS -> if (session != null) {
                session.expanded = intent.getBooleanExtra("expanded", false)
                if (session.expanded) focused = session.channel
                setQuality(session)
                publish()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publish() {
        val current = sessions.mapValues { (_, entry) ->
            LivePlaybackUi(entry.channel, entry.player, entry.loading, entry.message, entry.muted)
        }
        val selected = focused?.let(current::get)
        LivePlayback.mutablePlayers.value = current
        LivePlayback.mutable.value = selected?.takeIf { it.player != null }
            ?: current.values.firstOrNull { it.player != null } ?: selected ?: LivePlaybackUi()
    }

    private fun foreground() {
        val status = notification(sessions.keys.first())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, status)
        }
    }

    private fun setQuality(session: Session) {
        val player = session.player ?: return
        val (width, height) = if (session.expanded) 3840 to 2160 else 854 to 480
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(width, height).apply {
                if (session.expanded) clearViewportSizeConstraints()
            }.build()
    }

    private fun release(session: Session) {
        session.resolver?.cancel()
        val player = session.player
        session.player = null
        player?.release()
    }

    private fun load(session: Session, delayMillis: Long) {
        release(session)
        session.loading = true
        session.message = if (delayMillis > 0) "Reconectando…" else null
        publish()
        session.resolver = scope.launch {
            try {
                if (delayMillis > 0) delay(delayMillis)
                val source = request("/live/player", session.token, JSONObject().put("url", session.channel))
                val headers = source.optJSONObject("headers") ?: JSONObject()
                val properties = headers.keys().asSequence().associateWith { headers.getString(it) }
                val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(properties)
                val item = MediaItem.Builder().setUri(source.getString("url"))
                    .setMediaId(session.channel).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                val playback = ExoPlayer.Builder(this@LivePlaybackService).build().apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    setWakeMode(C.WAKE_MODE_NETWORK)
                    volume = if (session.muted) 0f else 1f
                }
                session.player = playback
                setQuality(session)
                playback.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (session.player !== playback) return
                        if (state == Player.STATE_READY) {
                            session.retries = 0
                            session.loading = false
                            session.message = null
                            publish()
                        }
                        if (state == Player.STATE_ENDED) scope.launch {
                            if (session.player === playback) finish(session, "A transmissão terminou.")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        scope.launch {
                            if (session.player === playback) retry(session, "A reprodução foi interrompida.")
                        }
                    }
                })
                publish()
                playback.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(item))
                playback.prepare()
                playback.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retry(session, e.message ?: "Não foi possível abrir a live.")
            }
        }
    }

    private fun retry(session: Session, reason: String) {
        if (sessions[session.channel] !== session) return
        if (session.retries >= 3) finish(session, reason)
        else {
            session.retries += 1
            load(session, session.retries * 4000L)
        }
    }

    private fun finish(session: Session, message: String) {
        release(session)
        session.loading = false
        session.message = message
        publish()
    }

    override fun onDestroy() {
        scope.cancel()
        sessions.values.forEach(::release)
        sessions.clear()
        focused = null
        publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(channel: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID,"Reprodução de lives",NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(this,422,
            Intent(this,MainActivity::class.java).setAction(LivePlayback.SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (sessions.size > 1) "Kick · " + sessions.size + " players" else "Kick · " + channel.substringAfterLast('/'))
            .setContentText("Reprodução ao vivo")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Fechar players",LivePlayback.stopIntent(this))
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "editorclipesdss_live_playback"
        const val NOTIFICATION_ID = 1042
    }
}
