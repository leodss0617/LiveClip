package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.util.Base64
import org.json.JSONArray
import java.util.UUID
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.service.LivePlayback

private val codespaceName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

internal class CodespacesSession(context: Context) {
    internal val preferences = context.getSharedPreferences("codespaces", Context.MODE_PRIVATE)
    var name by mutableStateOf(preferences.getString("name", "").orEmpty())
        private set
    var autoConnect by mutableStateOf(preferences.getBoolean("auto_connect", true))
        private set
    var preferCloud by mutableStateOf(preferences.getBoolean("prefer_cloud", false))
        private set
    fun connect(context: Context, live: String? = null) {
        if (name.length > 100 || !codespaceName.matches(name)) return
        val channel = live?.let(LivePlayback::channel)
        if (live != null && channel == null) return
        val pending = preferences.getBoolean("monitor_pending", false) &&
            System.currentTimeMillis() - preferences.getLong("started", 0) < 200_000
        if (pending) return
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") !=
            PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putString("status", "Conecte o Termux na tela Lives para autorizar os comandos.").apply()
            return
        }
        val requestId = UUID.randomUUID().toString()
        preferences.edit().putString("request", requestId).putBoolean("monitor_pending", channel != null)
            .putLong("started", System.currentTimeMillis())
            .putString("status", if (channel == null) "Conectando ao Codespace…" else "Enviando a live ao Codespace…")
            .apply()
        runCatching {
            val script = context.assets.open("codespace_monitor.py").use { it.readBytes() }
            val payload = JSONArray().put(if (channel == null) "prepare" else "monitor").put(channel)
            val code = Base64.encodeToString(script, Base64.NO_WRAP)
            val argument = Base64.encodeToString(payload.toString().toByteArray(), Base64.NO_WRAP)
            val remote = "cd /workspaces/LiveClip && " +
                "(curl -fsS --max-time 2 http://127.0.0.1:8080/api/health >/dev/null || " +
                "bash .devcontainer/start.sh retomar >&2) && " +
                "printf %s $code | base64 -d | python3 - $argument"
            runTermux(context, "/data/data/com.termux/files/home", arrayOf(
                "-lc", "GH_PROMPT_DISABLED=1 timeout 180 gh codespace ssh -c \"\$1\" -- \"\$2\"",
                "liveclip", name, remote
            ), false, requestId)
        }.onFailure {
            preferences.edit().remove("request").putBoolean("monitor_pending", false)
                .putString("status", "Não foi possível iniciar a conexão pelo Termux.").apply()
        }
    }

    fun configure(name: String, autoConnect: Boolean, preferCloud: Boolean) {
        this.name = name
        this.autoConnect = autoConnect
        this.preferCloud = preferCloud
        preferences.edit().putString("name", name).putBoolean("auto_connect", autoConnect)
            .putBoolean("prefer_cloud", preferCloud).apply()
    }
}

@Composable
internal fun rememberCodespacesSession(): CodespacesSession {
    val context = LocalContext.current
    return remember(context) { CodespacesSession(context) }
}

@Composable
internal fun CodespacesScreen(session: CodespacesSession, requestedLive: String? = null) {
    val context = LocalContext.current
    val channel = requestedLive?.let(LivePlayback::channel)
    var configuring by rememberSaveable { mutableStateOf(session.name.isEmpty()) }
    var name by rememberSaveable { mutableStateOf(session.name) }
    var autoConnect by rememberSaveable { mutableStateOf(session.autoConnect) }
    var preferCloud by rememberSaveable { mutableStateOf(session.preferCloud) }
    var cloudStatus by remember(session) { mutableStateOf(session.preferences.getString("status", "").orEmpty()) }
    DisposableEffect(session) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "status") cloudStatus = prefs.getString(key, "").orEmpty()
        }
        session.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { session.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var status by remember { mutableStateOf("") }

    fun open(url: String) {
        val uri = Uri.parse(url)
        val opened = openAuthenticatedBrowserTab(context, uri) ||
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess
        status = if (opened) "" else "Nenhum navegador disponível."
    }

    fun openPanel() {
        val uri = Uri.parse("https://${session.name}-8080.app.github.dev/").buildUpon()
        if (channel != null) uri.encodedFragment("live=" + Uri.encode(channel))
        open(uri.build().toString())
    }

    val configured = session.name.length <= 100 && codespaceName.matches(session.name)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (configuring || !configured) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nome completo do Codespace") },
                supportingText = { Text("Use o nome que aparece antes de .github.dev.") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preparar Codespace ao abrir o app", Modifier.weight(1f))
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
            ) { Text("Salvar") }
        } else {
            Text("Painel do LiveClip", style = MaterialTheme.typography.titleLarge)
            if (cloudStatus.isNotBlank()) Text(cloudStatus)
            OutlinedButton(onClick = { session.connect(context) }) { Text("Testar conexão") }
            if (channel != null) {
                Text(channel)
                Button(onClick = { session.connect(context, channel) }) { Text("Enviar monitoramento") }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Link da live", channel))
                    status = "Link copiado. Cole no painel se o campo estiver vazio."
                }) { Text("Copiar link da live") }
            }
            Button(onClick = { openPanel() }) { Text("Abrir painel") }
            OutlinedButton(onClick = { open("https://${session.name}.github.dev/") }) {
                Text("Abrir terminal")
            }
            TextButton(onClick = { configuring = true }) { Text("Ajustes") }
        }
        if (status.isNotBlank()) Text(status)
    }
}
