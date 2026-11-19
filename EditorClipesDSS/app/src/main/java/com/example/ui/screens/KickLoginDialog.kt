package com.example.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun isKickPage(uri: Uri): Boolean =
    uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
        uri.host.orEmpty().lowercase().let { it == "kick.com" || it.endsWith(".kick.com") }

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KickLoginDialog(browserUrl: String = "https://kick.com/", onDismiss: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Conta Kick", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
                Text("Toque em Entrar na página da Kick e use seu e-mail e senha.",
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { page?.reload(); error = "" }) { Text("Recarregar") }
                    TextButton(onClick = {
                        if (!openOfficialStreamingBrowser(context, browserUrl)) {
                            error = "Abra a Kick em um navegador compatível para entrar com Google."
                        }
                    }) { Text("Usar Google") }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (isKickPage(request.url)) return false
                                    if (request.isForMainFrame) {
                                        error = "Use «Usar Google» para autenticação externa."
                                    }
                                    return true
                                }

                                override fun onReceivedError(
                                    view: WebView, request: WebResourceRequest, failure: WebResourceError
                                ) {
                                    if (request.isForMainFrame) error = "A página da Kick não carregou. Toque em Recarregar."
                                }
                            }
                            loadUrl("https://kick.com/")
                            page = this
                        }
                    },
                    onRelease = {
                        CookieManager.getInstance().flush()
                        it.stopLoading()
                        it.destroy()
                    }
                )
            }
        }
    }
}
