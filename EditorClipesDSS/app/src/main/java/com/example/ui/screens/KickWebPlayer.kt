package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Player oficial de Kick, independente do servidor que processa cortes no Termux. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KickWebPlayer(channel: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
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
                        val uri = request.url
                        val host = uri.host.orEmpty().lowercase()
                        if (uri.scheme == "https" && (host == "kick.com" || host.endsWith(".kick.com"))) return false
                        if (uri.scheme == "https") {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }
                        return true
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
