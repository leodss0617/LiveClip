package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

private data class PublicChatLine(val id: String, val author: String, val content: String)

/** Chat público Kick: consulta o servidor local; mensagens legíveis em Compose.
 * A fonte é uma API web pública não oficial da Kick, que pode deixar de funcionar.
 * Conversar abre o chat da plataforma em WebView separado, com cookies de login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KickChatPanel(
    channel: String,
    modifier: Modifier = Modifier,
    onChatActivity: (Int, Float) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val uri = remember(channel) { runCatching { Uri.parse(channel) }.getOrNull() }
    val host = uri?.host.orEmpty().lowercase(Locale.ROOT)
    val slug = uri?.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
    val isKick = host == "kick.com" || host == "www.kick.com"
    val isTwitch = host == "twitch.tv" || host == "www.twitch.tv"
    val valid = uri?.scheme == "https" &&
        slug.matches(Regex("[a-z0-9_-]{2,40}")) && (isKick || isTwitch)
    val roomUrl = when {
        isKick && valid -> "https://kick.com/popout/$slug/chat"
        isTwitch && valid -> "https://www.twitch.tv/popout/$slug/chat?popout="
        else -> ""
    }
    val prefs = remember { ctx.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    val branding = remember { ctx.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    val onChatSignal by rememberUpdatedState(onChatActivity)
    val opacity = remember { branding.getFloat("chat_overlay_opacity", .96f).coerceIn(.92f, 1f) }
    val showLimit = remember { branding.getInt("chat_overlay_lines", 14).coerceIn(12, 22) }
    val scrollState = rememberScrollState()
    var recent by remember(channel) { mutableStateOf(emptyList<PublicChatLine>()) }
    var status by remember(channel) { mutableStateOf("Conectando às mensagens…") }
    var lastSuccessfulPoll by remember(channel) { mutableStateOf("") }
    var live by remember(channel) { mutableStateOf(false) }
    var externalChat by remember(channel) { mutableStateOf(isTwitch) }
    var lastSeen by remember(channel) { mutableStateOf(emptySet<String>()) }
    var refreshKey by remember(channel) { mutableIntStateOf(0) }
    var initialized by remember(channel) { mutableStateOf(false) }
    var webView by remember(channel) { mutableStateOf<WebView?>(null) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "token") token = prefs.getString("token", "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(channel, token, externalChat, refreshKey) {
        if (!valid || !isKick || externalChat) return@LaunchedEffect
        if (token.isBlank()) {
            status = "Conecte o Termux em Ajustes para ler o chat."
            return@LaunchedEffect
        }
        while (true) {
            try {
                val response = request("/chat/kick/$slug", token)
                val entries = response.optJSONArray("mensagens")
                val parsed = (0 until (entries?.length() ?: 0)).mapNotNull { index ->
                    val item = entries?.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val author = item.optString("autor").trim()
                    val message = item.optString("mensagem").trim()
                    if (id.isBlank() || author.isBlank() || message.isBlank()) null
                    else PublicChatLine(id, author.take(40), message.take(300))
                }
                live = response.optBoolean("ao_vivo", false)
                val keys = parsed.map { it.id }.toSet()
                val changed = keys != lastSeen
                lastSuccessfulPoll = java.text.SimpleDateFormat(
                    "HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                if (initialized && live && !response.optBoolean("atrasado", false)) {
                    val fresh = parsed.filter { it.id !in lastSeen }
                    if (fresh.isNotEmpty()) {
                        val excited = fresh.count { msg ->
                            msg.content.contains("kkkk", ignoreCase = true) ||
                            msg.content.contains("😂") || msg.content.contains("🔥") ||
                            msg.content.contains("😱") ||
                            msg.content.count { it == '!' || it == '?' } >= 2
                        }
                        onChatSignal(fresh.size.coerceAtMost(200),
                            excited.toFloat() / fresh.size)
                    }
                }
                initialized = true
                lastSeen = keys
                recent = parsed.takeLast(showLimit)
                status = when {
                    response.optBoolean("atrasado", false) ->
                        "Chat atrasado · última consulta: $lastSuccessfulPoll · reconectando"
                    !live -> "Canal fora do ar · consulta às $lastSuccessfulPoll"
                    parsed.isEmpty() -> "Sem mensagens recebidas · consulta às $lastSuccessfulPoll"
                    changed -> "Novas mensagens às $lastSuccessfulPoll · chat ao vivo"
                    else -> "Consulta às $lastSuccessfulPoll · sem mensagens novas"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e.message ?: e.javaClass.simpleName).take(120)
                status = "Falha ao consultar Kick: $reason · tentando novamente"
            }
            delay(4500)
        }
    }

    LaunchedEffect(recent) { scrollState.scrollTo(scrollState.maxValue) }
    Column(modifier.background(Color(0xFF0B1220), RoundedCornerShape(10.dp))
        .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (isTwitch) "Chat Twitch" else "Chat Kick",
                color = Color.White, style = MaterialTheme.typography.labelMedium)
            if (isKick) TextButton(onClick = { status = "Atualizando mensagens…"; refreshKey++ },
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp)) {
                Text("Atualizar", color = Color.White, fontSize = 12.sp)
            }
            TextButton(onClick = {
                if (isKick || isTwitch) {
                    // Chat autenticado em painel seguro integrado, sem WebView do Google.
                    val address = if (isKick) "https://kick.com/$slug"
                                  else "https://www.twitch.tv/$slug"
                    if (!openOfficialStreamingBrowser(ctx, address)) {
                        status = "Não foi possível abrir o painel integrado de chat. Verifique o Chrome."
                    }
                } else externalChat = !externalChat
            }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)) {
                Text(if (isKick) "Conversar no site" else if (externalChat) "Mensagens" else "Conversar",
                    color = Color(0xFF8FDFFF), fontSize = 12.sp)
            }
        }
        if (!valid) {
            Text("Link de canal inválido.", color = Color.White)
        } else if (externalChat || isTwitch) {
            // Chat completo somente quando solicitado. Ao contrário da versão
            // anterior, o site não fica invisível ocupando toda a superfície.
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF10131B)),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.rgb(16,19,27))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.textZoom = 105
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView,
                                request: WebResourceRequest): Boolean {
                                val h = request.url.host.orEmpty().lowercase(Locale.ROOT)
                                val allowed = h == "kick.com" || h.endsWith(".kick.com") ||
                                    h == "twitch.tv" || h.endsWith(".twitch.tv")
                                return request.url.scheme != "https" || !allowed
                            }
                        }
                        loadUrl(roomUrl)
                        webView = this
                    }
                },
                onRelease = { view ->
                    CookieManager.getInstance().flush()
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                }
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
                verticalArrangement = Arrangement.Bottom) {
                if (status.isNotBlank()) {
                    Text(status, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.Black.copy(alpha = .78f),
                                RoundedCornerShape(6.dp))
                            .padding(6.dp))
                    Spacer(Modifier.height(3.dp))
                }
                recent.forEach { message ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF7BD7FF),
                                fontWeight = FontWeight.Bold)) {
                                append(message.author)
                            }
                            append(": ")
                            append(message.content)
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(Color.Black.copy(alpha = opacity),
                                RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
