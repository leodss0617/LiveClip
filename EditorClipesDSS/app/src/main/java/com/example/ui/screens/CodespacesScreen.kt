package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.material3.FilterChip
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

private val codespaceName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

internal class CodespacesSession(context: Context) {
    private val preferences = context.getSharedPreferences("codespaces", Context.MODE_PRIVATE)
    var name by mutableStateOf(preferences.getString("name", "").orEmpty())
        private set
    var autoConnect by mutableStateOf(preferences.getBoolean("auto_connect", true))
        private set
    val terminal = CodespacesPage(context)
    val monitor = CodespacesPage(context)

    fun configure(name: String, autoConnect: Boolean) {
        val changed = this.name != name
        this.name = name
        this.autoConnect = autoConnect
        preferences.edit().putString("name", name).putBoolean("auto_connect", autoConnect).apply()
        if (changed) {
            terminal.close()
            monitor.close()
        }
    }

    fun openTerminal() {
        if (name.isNotEmpty()) terminal.load("https://$name.github.dev/")
    }

    fun openMonitor() {
        if (name.isNotEmpty()) monitor.load("https://$name-8080.app.github.dev/")
    }

    fun close() {
        terminal.close()
        monitor.close()
    }
}

internal class CodespacesPage(private val context: Context) {
    var view by mutableStateOf<WebView?>(null)
        private set
    var error by mutableStateOf(false)
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun load(url: String) {
        if (view != null) return
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
    }
}

@Composable
internal fun rememberCodespacesSession(): CodespacesSession {
    val context = LocalContext.current
    val session = remember(context) { CodespacesSession(context) }
    LaunchedEffect(session) {
        if (session.autoConnect) session.openTerminal()
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    return session
}

@Composable
internal fun CodespacesScreen(session: CodespacesSession) {
    var monitoring by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf(session.name.isEmpty()) }
    var name by remember { mutableStateOf(session.name) }
    var autoConnect by remember { mutableStateOf(session.autoConnect) }
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
                Button(
                    enabled = name.trim().length <= 100 && codespaceName.matches(name.trim()),
                    onClick = {
                        session.configure(name.trim(), autoConnect)
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
            FilterChip(!monitoring, { monitoring = false }, { Text("Terminal") })
            FilterChip(monitoring, { monitoring = true }, { Text("Lives na nuvem") })
            TextButton(onClick = { configuring = true }) { Text("Configurar") }
        }

        LaunchedEffect(session.name, monitoring) {
            if (monitoring) session.openMonitor() else session.openTerminal()
        }

        val page = if (monitoring) session.monitor else session.terminal
        if (page.error) {
            Text("Falha ao conectar ao Codespace.", Modifier.padding(horizontal = 12.dp))
        }
        if (monitoring) {
            Text(
                "Cadastre a live no painel para acompanhar a captura e os cortes no Codespace.",
                Modifier.padding(horizontal = 12.dp)
            )
        }
        TextButton(onClick = { page.view?.reload() }) { Text("Recarregar") }
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
