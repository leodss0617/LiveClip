package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.example.service.LivePlayback
import org.json.JSONObject

private val codespaceName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

internal class CodespacesSession(context: Context) {
    private val preferences = context.getSharedPreferences("codespaces", Context.MODE_PRIVATE)
    var name by mutableStateOf(preferences.getString("name", "").orEmpty())
        private set
    var autoConnect by mutableStateOf(preferences.getBoolean("auto_connect", true))
        private set
    var preferCloud by mutableStateOf(preferences.getBoolean("prefer_cloud", false))
        private set
    val monitor = CodespacesPage(context)

    fun configure(name: String, autoConnect: Boolean, preferCloud: Boolean) {
        val changed = this.name != name
        this.name = name
        this.autoConnect = autoConnect
        this.preferCloud = preferCloud
        preferences.edit().putString("name", name).putBoolean("auto_connect", autoConnect)
            .putBoolean("prefer_cloud", preferCloud).apply()
        if (changed) {
            monitor.close()
        }
    }



    fun openMonitor() {
        if (name.isNotEmpty()) monitor.load("https://$name-8080.app.github.dev/")
    }

    fun close() {
        monitor.close()
    }
}

internal class CodespacesPage(private val context: Context) {
    var view by mutableStateOf<WebView?>(null)
        private set
    var error by mutableStateOf(false)
        private set

    var ready by mutableStateOf(false)
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun load(url: String) {
        if (view != null) return
        val panelHost = Uri.parse(url).host
        view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val host = target.host ?: return true
                    return target.scheme != "https" || target.userInfo != null ||
                        (host != "github.com" && !host.endsWith(".github.com") &&
                            !host.endsWith(".github.dev"))
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    error = false
                    ready = false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    ready = !error && Uri.parse(url).host == panelHost
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    if (request.isForMainFrame) error = true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) error = true
                }
            }
            loadUrl(url)
        }
    }

    fun close() {
        view?.let {
            (it.parent as? FrameLayout)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        view = null
        error = false
        ready = false
    }
}

@Composable
internal fun rememberCodespacesSession(): CodespacesSession {
    val context = LocalContext.current
    val session = remember(context) { CodespacesSession(context) }
    LaunchedEffect(session) {
        if (session.autoConnect) session.openMonitor()
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    return session
}

@Composable
internal fun CodespacesScreen(
    session: CodespacesSession, requestedLive: String? = null
) {
    val context = LocalContext.current
    var browserError by remember { mutableStateOf(false) }
    var reloadCount by remember { mutableStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }
    val channel = requestedLive?.let(LivePlayback::channel)
    var prepared by remember(channel) { mutableStateOf(false) }
    fun openInBrowser(url: String) {
        browserError = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isFailure
    }
    var configuring by remember { mutableStateOf(session.name.isEmpty()) }
    var name by remember { mutableStateOf(session.name) }
    var autoConnect by remember { mutableStateOf(session.autoConnect) }
    var preferCloud by remember { mutableStateOf(session.preferCloud) }
    Column(Modifier.fillMaxSize()) {
        if (configuring) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("GitHub Codespaces")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome completo do Codespace") },
                    supportingText = { Text("Use o nome no endereço do editor, antes de .github.dev.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Conectar ao abrir o app", Modifier.weight(1f))
                    Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preferir Codespaces para novas lives", Modifier.weight(1f))
                    Switch(checked = preferCloud, onCheckedChange = { preferCloud = it })
                }
                Button(
                    enabled = name.trim().length <= 100 && codespaceName.matches(name.trim()),
                    onClick = {
                        session.configure(name.trim(), autoConnect, preferCloud)
                        configuring = false
                    }
                ) { Text("Conectar") }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { reloadCount++; session.monitor.view?.reload() }) { Text("Recarregar") }
            TextButton(onClick = {
                openInBrowser("https://${session.name}.github.dev/")
            }) { Text("Terminal") }
            TextButton(onClick = { configuring = true }) { Text("Ajustes") }
        }

        LaunchedEffect(session.name) { session.openMonitor() }
        val page = session.monitor
        LaunchedEffect(session.name, reloadCount) {
            timedOut = false
            delay(25_000)
            timedOut = !page.ready
        }
        LaunchedEffect(channel, page.ready, page.view, session.name) {
            val view = page.view ?: return@LaunchedEffect
            val panelHost = "${session.name}-8080.app.github.dev"
            if (channel == null || !page.ready || Uri.parse(view.url.orEmpty()).host != panelHost) {
                return@LaunchedEffect
            }
            val target = JSONObject.quote(channel)
            val origin = JSONObject.quote("https://$panelHost")
            view.evaluateJavascript(
                """(() => {
                    if (location.origin !== $origin) return false;
                    const input = document.querySelector('#source-form #url');
                    if (!input || (input.value && input.value !== $target)) return false;
                    input.value = $target;
                    return input.value === $target;
                })()"""
            ) { result ->
                if (result == "true") {
                    prepared = true
                }
            }
        }
        if (channel != null) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            Text(
                if (prepared) "Link preenchido. Confirme em Processar link."
                else "Live na nuvem · ${channel.substringAfterLast('/')}",
                Modifier.padding(horizontal = 12.dp)
            )
            TextButton(onClick = {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Live", channel))
            }) { Text("Copiar link da live") }
        }
        if (browserError) Text("Nenhum navegador disponível.", Modifier.padding(12.dp))
        if (page.error || (timedOut && !page.ready)) {
            Text("O painel da nuvem demorou a responder.", Modifier.padding(horizontal = 12.dp))
            TextButton(onClick = { openInBrowser("https://${session.name}-8080.app.github.dev/") }) {
                Text("Abrir painel no navegador")
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { FrameLayout(it) },
            update = { container ->
                val view = page.view
                if (container.getChildAt(0) !== view) {
                    container.removeAllViews()
                    if (view != null) {
                        (view.parent as? FrameLayout)?.removeView(view)
                        container.addView(view, FrameLayout.LayoutParams(-1, -1))
                    }
                }
            },
            onRelease = { it.removeAllViews() }
        )
    }
}
