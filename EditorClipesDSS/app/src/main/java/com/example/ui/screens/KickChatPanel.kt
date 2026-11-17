package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

private fun kickChatUrl(channel: String): String? {
    val slug = Uri.parse(channel).lastPathSegment.orEmpty().trim().lowercase()
    if (!slug.matches(Regex("[a-z0-9_-]{1,32}"))) return null
    return "https://kick.com/popout/$slug/chat"
}

/**
 * Kick usa a mesma página de chat popout documentada para docks OBS.
 * Uma sessão Kick pode ser estabelecida no próprio WebView quando o
 * provedor autorizar; nenhuma senha é capturada pelo EditorClipesDSS.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KickChatPanel(channel: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chatUrl = remember(channel) { kickChatUrl(channel) }
    var loginMode by remember(channel) { mutableStateOf(false) }
    var webView by remember(channel) { mutableStateOf<WebView?>(null) }
    var info by remember(channel) { mutableStateOf("") }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Chat da Kick", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                loginMode = false
                chatUrl?.let { webView?.loadUrl(it) }
            }, modifier = Modifier.weight(1f)) { Text("Chat") }
            OutlinedButton(onClick = {
                loginMode = true
                webView?.loadUrl("https://kick.com/")
            }, modifier = Modifier.weight(1f)) { Text("Entrar") }
        }
        if (chatUrl == null) {
            Text("Canal Kick inválido.", color = MaterialTheme.colorScheme.error)
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                val uri = request.url
                                // Não deixar a página redirecionar silenciosamente
                                // para domínios desconhecidos.
                                val host = uri.host.orEmpty().lowercase()
                                if (uri.scheme == "https" &&
                                    (host == "kick.com" || host.endsWith(".kick.com"))) return false
                                if (uri.scheme == "https") {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                }
                                return true
                            }
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) info = "Não foi possível carregar o chat."
                            }
                        }
                        loadUrl(if (loginMode) "https://kick.com/" else chatUrl)
                        webView = this
                    }
                },
                update = { view ->
                    if (webView !== view) webView = view
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                },
            )
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)
            Text(
                "O chat popout da Kick é para leitura. O botão Entrar abre o site oficial; " +
                    "para enviar mensagens, use a página normal do canal.",
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val slug = Uri.parse(chatUrl).pathSegments.getOrNull(1).orEmpty()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kick.com/" + slug)))
                }
            ) { Text("Abrir canal e conversar na Kick") }
        }
    }
}
