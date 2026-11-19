package com.example.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView

/** Player oficial de Kick, independente do servidor que processa cortes no Termux. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KickWebPlayer(channel: String, modifier: Modifier = Modifier) {
    val slug = remember(channel) { Uri.parse(channel).lastPathSegment.orEmpty().lowercase() }
    if (!slug.matches(Regex("[a-z0-9_-]{1,40}"))) {
        Text("Canal da Kick inválido.", color = MaterialTheme.colorScheme.error)
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return request.isForMainFrame && !isKickPage(request.url)
                    }
                }
                loadUrl("https://player.kick.com/$slug")
            }
        },
        onRelease = {
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        },
    )
}

@Composable
internal fun KickOfficialLiveDialog(channel: String, onDismiss: () -> Unit) {
    var reload by remember(channel) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.substringAfterLast('/'), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { reload++ }) { Text("Reconectar") }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
                key(reload) {
                    KickWebPlayer(channel, Modifier.fillMaxWidth().weight(1f))
                    KickChatPanel(channel, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
