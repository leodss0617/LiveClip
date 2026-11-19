package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.service.LivePlayback
import com.example.service.LivePlaybackUi
import com.example.ui.components.CortaJaPlayerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException

private data class LiveJob(
    val id: String,
    val channel: String,
    val status: String,
    val phase: String,
    val engine: String,
    val clips: List<String>,
) {
    val active: Boolean get() = status == "running" || status == "queued"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LivesScreen(
    incomingLink: String?,
    onLinkConsumed: () -> Unit,
    expanded: String?,
    onExpand: (String?) -> Unit,
    onTermux: (String?) -> Unit,
    onCloud: (String) -> Unit,
    preferCloud: Boolean,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var pairingStatus by remember { mutableStateOf(prefs.getString("pairing_status", "").orEmpty()) }
    val pairingPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { requestTermuxPairing(context) }
            .onFailure { pairingStatus = it.localizedMessage ?: "Termux indisponível." }
        else pairingStatus = "Autorize Executar comandos no Termux nas permissões do aplicativo."
    }
    fun pairTermux() {
        pairingStatus = "Conectando ao Termux…"
        prefs.edit().putString("pairing_status", pairingStatus).apply()
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED) {
            runCatching { requestTermuxPairing(context) }
                .onFailure { pairingStatus = it.localizedMessage ?: "Termux indisponível." }
        } else pairingPermission.launch("com.termux.permission.RUN_COMMAND")
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "token") token = prefs.getString("token", "").orEmpty()
            if (key == "pairing_status") pairingStatus = prefs.getString("pairing_status", "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val scope = rememberCoroutineScope()
    val players by LivePlayback.players.collectAsState()
    val channels = remember {
        val stored = prefs.getString("multi_live_channels", null)
        val links = if (stored == null) listOf(prefs.getString("link_ao_vivo", "").orEmpty()) else {
            val array = runCatching { JSONArray(stored) }.getOrDefault(JSONArray())
            List(array.length()) { array.optString(it) }
        }
        links.mapNotNull(LivePlayback::channel).distinct().take(LivePlayback.LIMIT).toMutableStateList()
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var jobs by remember { mutableStateOf(emptyMap<String, LiveJob>()) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf("") }
    var monitor by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    var officialLive by rememberSaveable { mutableStateOf<String?>(null) }
    var showClips by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // No modo paisagem, priorizar o chat Kick; ações ficam num menu compacto.
    var expandedControlsVisible by rememberSaveable { mutableStateOf(false) }
    var expandedChatVisible by rememberSaveable { mutableStateOf(false) }
    var autoPairAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token.isBlank() && !autoPairAttempted &&
            context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED) {
            autoPairAttempted = true
            pairTermux()
        }
    }
    var pendingNativeChannel by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingNativeChannel, players) {
        val pending = pendingNativeChannel ?: return@LaunchedEffect
        val player = players[pending] ?: return@LaunchedEffect
        if (player.player != null || player.loading) {
            pendingNativeChannel = null
            onExpand(pending)
        } else if (player.message != null) {
            message = player.message
            pendingNativeChannel = null
        }
    }


    fun saveChannels() {
        prefs.edit().putString("multi_live_channels", JSONArray(channels.toList()).toString()).apply()
    }

    fun addLink(raw: String) {
        val channel = LivePlayback.channel(raw)
        when {
            channel == null -> message = "Use o link de um canal da Kick."
            channel in channels -> message = "Esse canal já está na lista."
            channels.size >= LivePlayback.LIMIT -> message = "Remova um canal para adicionar outro."
            else -> {
                channels.add(channel)
                saveChannels()
                draft = ""
                message = ""
            }
        }
    }

    fun playbackAction(action: () -> Unit) {
        runCatching(action).onFailure { message = it.message ?: "Não foi possível controlar o player." }
    }

    suspend fun refreshJobs() {
        val list = request("/jobs/lives", token).getJSONArray("lives")
        jobs = List(list.length()) { index ->
            val item = list.getJSONObject(index)
            val clips = item.optJSONArray("arquivos") ?: JSONArray()
            LiveJob(
                item.getString("id"), item.getString("canal"), item.optString("status"),
                item.optString("fase"),
                item.optString("motor_ativo"),
                List(clips.length()) { clips.getString(it) },
            )
        }.associateBy(LiveJob::channel)
        reachable = true
    }

    fun runJob(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                action()
                refreshJobs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Não foi possível executar a ação."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(incomingLink) {
        if (incomingLink != null) {
            addLink(incomingLink)
            onLinkConsumed()
        }
    }
    LaunchedEffect(token) {
        reachable = null
        jobs = emptyMap()
        if (token.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                refreshJobs()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reachable = false
            }
            delay(3000)
        }
    }

    monitor?.let { channel ->
        LiveMonitorDialog(channel, busy, message, onDismiss = { monitor = null }, onCloud = {
            monitor = null
            onCloud(channel)
        }) { spec ->
            runJob {
                try {
                    val logo = context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE)
                        .getString("logo_uri", null)?.let(Uri::parse)
                    spec.put("logo", logo?.let { sendLogo(context, it, token) }.orEmpty())
                    request("/jobs", token, spec)
                    monitor = null
                } catch (_: ConnectException) {
                    reachable = false
                    monitor = null
                    onCloud(channel)
                }
            }
        }
    }
    @Composable
    fun MonitorAction(channel: String, job: LiveJob?) {
        if (job?.active == true) {
            LiveAction(Icons.Filled.Stop, "Parar", "Parar monitoramento", enabled = !busy && reachable == true) {
                runJob { request("/jobs/" + job.id + "/stop", token, JSONObject()) }
            }
        } else {
            LiveAction(Icons.Filled.ContentCut, "Monitorar", "Monitorar cortes", enabled = !busy) {
                if (!preferCloud && reachable != false && token.isNotBlank()) {
                    message = ""
                    monitor = channel
                } else {
                    onCloud(channel)
                }
            }
        }
    }

    @Composable
    fun ExpandedActions(channel: String, playback: LivePlaybackUi) {
        val job = jobs[channel]
        StreamControls(
            channel, playback, job, token, onExpand = null,
            leading = {
                LiveAction(Icons.Filled.FullscreenExit, "Voltar", "Voltar à lista de lives") { onExpand(null) }
                MonitorAction(channel, job)
            },
            trailing = {
                if (job?.status == "error") {
                    LiveAction(Icons.Filled.History, "Atividade", "Ver falha da última tentativa") { onTermux(job.id) }
                }
            },
        )
        if (job?.active == true) Text(job.phase, style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank() && monitor == null) Text(message, style = MaterialTheme.typography.bodySmall)
    }

    val activePlayer = expanded?.let(players::get)
    LaunchedEffect(expanded, activePlayer) {
        val channel = expanded ?: return@LaunchedEffect
        if (channel.startsWith("https://kick.com/") && activePlayer != null && activePlayer.player == null &&
            !activePlayer.loading && activePlayer.message != null &&
            activePlayer.message != "A transmissão terminou."
        ) {
            onExpand(null)
            officialLive = channel
        }
    }

    if (expanded != null && activePlayer != null) {
        val activity = context as? Activity
        DisposableEffect(expanded) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            playbackAction { LivePlayback.focus(context, expanded, true) }
            onDispose {
                if (previous != null) activity?.requestedOrientation = previous
                if (LivePlayback.players.value.containsKey(expanded)) {
                    playbackAction { LivePlayback.focus(context, expanded, false) }
                }
            }
        }
        BackHandler { onExpand(null) }
        val chatAnalysisEnabled = context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE)
            .getBoolean("chat_signal_enabled", true)
        LiveFullscreenOverlay(
            channel = expanded,
            playback = activePlayer,
            actions = { ExpandedActions(expanded, activePlayer) },
            onChatActivity = { count, excitement ->
                val activeJob = jobs[expanded]
                if (chatAnalysisEnabled && token.isNotBlank() && activeJob?.active == true) {
                    scope.launch {
                        try {
                            request("/jobs/" + activeJob.id + "/chat-signal", token,
                                JSONObject().put("mensagens", count).put("animacao", excitement.toDouble()))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A conexão do chat não interrompe a captura da live.
                        }
                    }
                }
            },
        )
        return
    }
    LaunchedEffect(expanded, activePlayer) {
        if (expanded != null && activePlayer == null) onExpand(null)
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var kickLogin by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { kickLogin = true }) { Text("Entrar na Kick") }
            if (kickLogin) KickLoginDialog(onDismiss = { kickLogin = false })
            if (channels.size < LivePlayback.LIMIT) {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Link da live na Kick ou Twitch") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                LiveAction(Icons.Filled.Add, "Adicionar", "Adicionar live", enabled = draft.isNotBlank()) {
                    addLink(draft)
                }
            }
            if (token.isBlank() || reachable == false) {
                Text(if (token.isBlank()) "Termux desconectado. Monitorar abre o painel do Codespaces."
                     else "Termux sem resposta. Monitorar abre o painel do Codespaces.",
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pairTermux() }) { Text("Conectar Termux") }
            } else if (reachable == true) {
                Text("Monitoramento pronto", color = MaterialTheme.colorScheme.primary,
                     style = MaterialTheme.typography.labelMedium)
            }
            if (pairingStatus.isNotBlank() && reachable != true) {
                Text(pairingStatus, style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message)
            for (channel in channels) key(channel) {
                val playback = players[channel]
                val job = jobs[channel]
                val opened = playback != null && (playback.player != null || playback.loading)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    StreamVideo(playback, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(channel.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                        StreamControls(
                            channel, playback, job, token, onExpand = { onExpand(channel) },
                            leading = {
                                if (opened) {
                                    LiveAction(Icons.Filled.Close, "Fechar", "Fechar player") {
                                        playbackAction { LivePlayback.stop(context, channel) }
                                    }
                                } else {
                                    LiveAction(Icons.Filled.PlayArrow, "Assistir", "Assistir à live") {
                                        if (channel.startsWith("https://kick.com/") &&
                                            (token.isBlank() || reachable != true || playback?.message != null)
                                        ) {
                                            officialLive = channel
                                        } else if (token.isNotBlank() && reachable == true) {
                                            playbackAction {
                                                pendingNativeChannel = channel
                                                LivePlayback.play(context, channel, token)
                                            }
                                        } else {
                                            if (!openOfficialStreamingBrowser(context, channel)) {
                                                message = "Abra a Twitch pelo navegador ou conecte o Termux."
                                            }
                                        }
                                    }
                                }
                            },
                            trailing = {
                                MonitorAction(channel, job)
                                if (reachable == true && job?.active != true) {
                                    LiveAction(
                                        if (preferCloud) Icons.Filled.ContentCut else Icons.Filled.Cloud,
                                        if (preferCloud) "Termux" else "Nuvem",
                                        if (preferCloud) "Monitorar no Termux" else "Monitorar no Codespaces",
                                        enabled = !busy
                                    ) {
                                        if (preferCloud) {
                                            message = ""
                                            monitor = channel
                                        } else onCloud(channel)
                                    }
                                }
                                if (job != null) {
                                    if (job.clips.isNotEmpty()) {
                                        LiveAction(Icons.Filled.VideoLibrary, "Clipes", "Abrir " + job.clips.size + " clipes",
                                            count = job.clips.size) { showClips = channel }
                                    }
                                    LiveAction(Icons.Filled.History, "Atividade", "Ver atividade do canal") {
                                        onTermux(job.id)
                                    }
                                }
                                LiveAction(Icons.Filled.RemoveCircleOutline, "Remover", "Remover canal") { remove = channel }
                            },
                        )
                        when {
                            job?.active == true -> {
                                val engine = when (job.engine) {
                                    "pc" -> "PC"
                                    "termux" -> "Termux"
                                    else -> null
                                }
                                if (engine != null) Text("Motor: $engine", style = MaterialTheme.typography.labelMedium)
                                Text(job.phase, style = MaterialTheme.typography.bodySmall)
                            }
                            job?.status == "error" -> Text("Última tentativa falhou. Veja Atividade.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    officialLive?.let { channel -> KickOfficialLiveDialog(channel) { officialLive = null } }

    remove?.let { channel ->
        val active = jobs[channel]?.active == true
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remover " + channel.substringAfterLast('/') + "?") },
            text = { Text(if (active) "Pare o monitoramento para remover esse canal." else "O player desse canal será fechado.") },
            confirmButton = {
                TextButton(enabled = !active && reachable != false, onClick = {
                    if (players.containsKey(channel)) playbackAction { LivePlayback.stop(context, channel) }
                    channels.remove(channel)
                    saveChannels()
                    remove = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancelar") } },
        )
    }
    showClips?.let { channel ->
        val job = jobs[channel]
        AlertDialog(
            onDismissRequest = { showClips = null },
            title = { Text("Clipes · " + channel.substringAfterLast('/')) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (job != null) for (clip in job.clips) {
                        val title = clip.removeSuffix(".mp4").substringBefore("_nota-").replace('_', ' ').replace('-', ' ')
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                                runJob {
                                    preview = clipFile(context, job.id, clip, token) to title
                                    showClips = null
                                }
                            }) { Text(title, maxLines = 2) }
                            OutlinedButton(enabled = !busy, onClick = {
                                runJob {
                                    val video = clipFile(context, job.id, clip, token)
                                    if (!shareClipToTikTok(context, video))
                                        message = "TikTok não encontrado; escolha um aplicativo."
                                }
                            }) { Text("TikTok") }
                        }
                    }
                    if (message.isNotBlank()) Text(message)
                }
            },
            confirmButton = { TextButton(onClick = { showClips = null }) { Text("Fechar") } },
        )
    }
    preview?.let { (uri, title) ->
        CortaJaPlayerDialog(uri, title, confirmActionText = "Compartilhar", onConfirmAction = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar clipe"))
        }, onDismiss = { preview = null })
    }
}

@Composable
private fun LiveFullscreenOverlay(
    channel: String,
    playback: LivePlaybackUi,
    actions: @Composable () -> Unit,
    onChatActivity: (Int, Float) -> Unit,
) {
        // Vídeo em tela inteira. O painel é sobreposto, nunca reserva espaço fixo.
        var sidePanel by rememberSaveable(channel) { mutableStateOf("chat") }
        var dragAmount by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize()
            .pointerInput(channel) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, change -> dragAmount += change },
                    onDragEnd = {
                        if (dragAmount < -55f) sidePanel = "actions"
                        if (dragAmount > 55f) sidePanel = "none"
                        dragAmount = 0f
                    },
                    onDragCancel = { dragAmount = 0f },
                )
            }
        ) {
            StreamVideo(playback, Modifier.fillMaxSize(), controls = false)
            // Alça pequena na borda, sempre visível para descobrir o menu.
            Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                IconButton(onClick = {
                    sidePanel = if (sidePanel == "chat") "none" else "chat"
                }, modifier = Modifier.size(42.dp).background(Color.Black.copy(alpha=.48f), RoundedCornerShape(12.dp))) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = "Mostrar chat sobre a live", tint = Color.White)
                }
                IconButton(onClick = {
                    sidePanel = if (sidePanel == "actions") "none" else "actions"
                }, modifier = Modifier.size(42.dp).background(Color.Black.copy(alpha=.48f), RoundedCornerShape(12.dp))) {
                    Icon(Icons.Filled.Menu, contentDescription = "Puxar menu lateral", tint = Color.White)
                }
            }
            if (sidePanel != "none") {
                Column(Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight(if (sidePanel == "chat") .88f else 1f)
                    .fillMaxWidth(if (sidePanel == "chat") .52f else .43f)
                    .background(Color.Black.copy(alpha = if (sidePanel == "chat") .93f else .82f),
                        RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sidePanel == "chat") "Chat ao vivo · mensagens legíveis" else "Controles",
                            style = MaterialTheme.typography.labelMedium, color = Color.White)
                        IconButton(onClick = { sidePanel = "none" }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Fechar painel", tint = Color.White)
                        }
                    }
                    if (sidePanel == "chat") {
                        KickChatPanel(
                            channel = channel,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onChatActivity = onChatActivity,
                        )
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                            actions()
                        }
                    }
                }
            }
        }
}

@Composable
private fun StreamVideo(playback: LivePlaybackUi?, modifier: Modifier, controls: Boolean = false) {
    val player = playback?.player
    if (player == null) Box(modifier, contentAlignment = Alignment.Center) {
        Text(playback?.message ?: if (playback?.loading == true) "Conectando…" else "Toque em Assistir para abrir a transmissão",
            modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    } else LiveVideoSurface(player, modifier, controls)
}

@Composable
private fun LiveAction(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean = true,
    count: Int? = null,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 34.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            if (count == null) label else "$label ($count)",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamControls(
    channel: String,
    playback: LivePlaybackUi?,
    job: LiveJob?,
    token: String,
    onExpand: (() -> Unit)?,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = playback?.player
    var ready by remember(player) { mutableStateOf(false) }
    var height by remember(player) { mutableStateOf(0) }
    var sending by remember(job?.id) { mutableStateOf(false) }
    var feedback by remember(channel, job?.id) { mutableStateOf("") }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        fun update() {
            ready = player.playbackState == Player.STATE_READY && playedMoment(player) != null
            height = player.videoSize.height
        }
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { update() } }
        player.addListener(listener)
        update()
        onDispose { player.removeListener(listener) }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading()
        if (player != null && playback != null) {
            val muted = playback.muted
            LiveAction(
                if (muted) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                if (muted) "Som" else "Mudo",
                if (muted) "Ativar som" else "Silenciar a live",
            ) {
                runCatching { LivePlayback.mute(context, channel) }.onFailure {
                    feedback = "Não foi possível alterar o áudio."
                }
            }
            if (onExpand != null) LiveAction(
                Icons.Filled.Fullscreen, "Expandir", "Expandir player em paisagem", onClick = onExpand,
            )
        }
        if (job?.status == "running" || onExpand == null) {
            LiveAction(Icons.Filled.BookmarkBorder, "Marcar", "Marcar momento da live",
                enabled = job?.status == "running" && ready && !sending) {
                val currentJob = job ?: return@LiveAction
                val mark = player?.let(::playedMoment) ?: return@LiveAction
                mark.put("canal", channel)
                sending = true
                scope.launch {
                    try {
                        request("/jobs/" + currentJob.id + "/mark", token, mark)
                        feedback = "Momento marcado."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        feedback = e.message ?: "Não consegui marcar o momento."
                    } finally {
                        sending = false
                    }
                }
            }
        }
        trailing()
    }
    if (onExpand == null && height > 0) Text("Qualidade: " + height + "p", style = MaterialTheme.typography.bodySmall)
    if (onExpand == null && job?.status == "queued") {
        Text("O botão fica disponível quando a captura começar.", style = MaterialTheme.typography.bodySmall)
    }
    if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveMonitorDialog(
    channel: String, busy: Boolean, message: String, onDismiss: () -> Unit,
    onCloud: () -> Unit, onConfirm: (JSONObject) -> Unit
) {
    val count = 0 // monitoramento contínuo até encerrar a live ou usuário parar
    var minutes by rememberSaveable(channel) { mutableStateOf(4) }
    var effects by rememberSaveable(channel) { mutableStateOf(false) }
    var trends by rememberSaveable(channel) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Cortes · " + channel.substringAfterLast('/')) },
        text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(enabled = !busy, onClick = onCloud) { Text("Usar Codespaces") }
                Text("O monitoramento contínuo no celular usa o Termux e aceita até 2 lives simultâneas.", style = MaterialTheme.typography.bodySmall)
                Text("Clipes automáticos enquanto a live durar", style = MaterialTheme.typography.titleSmall)
                Text("Sem quantidade fixa. Exporta apenas momentos com nota acima de 7,5, " +
                    "considerando começo, desenvolvimento e desfecho. Para ao encerrar a live, " +
                    "ao tocar em Parar ou quando faltar espaço no aparelho.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Duração máxima")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..4).forEach { value ->
                        FilterChip(minutes == value, onClick = { minutes = value }, label = { Text("$value min") })
                    }
                }
                Text("Câmera detectada automaticamente", style = MaterialTheme.typography.titleSmall)
                Text("O sistema tenta localizar o quadro do streamer. " +
                    "Streamer em cima e conteúdo principal embaixo, sem espaço entre os dois.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(effects, { effects = it })
                    Text("Emojis e efeitos", Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(trends, { trends = it })
                    Text("Assuntos em alta", Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                onConfirm(JSONObject().put("url", channel).put("modo", "ao_vivo").put("motor", "pc")
                    .put("quantidade", count).put("duracao", minutes * 60).put("camera", "auto")
                    .put("efeitos", effects).put("tendencias", trends))
            }) { Text(if (busy) "Iniciando…" else "Iniciar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
    )
}
