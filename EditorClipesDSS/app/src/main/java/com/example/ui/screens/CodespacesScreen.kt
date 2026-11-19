package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private val preferences = context.getSharedPreferences("codespaces", Context.MODE_PRIVATE)
    var name by mutableStateOf(preferences.getString("name", "").orEmpty())
        private set
    var autoConnect by mutableStateOf(preferences.getBoolean("auto_connect", true))
        private set
    var preferCloud by mutableStateOf(preferences.getBoolean("prefer_cloud", false))
        private set
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
    var attempted by rememberSaveable(session.name, channel) { mutableStateOf(false) }
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
    LaunchedEffect(configured, configuring, session.autoConnect, channel) {
        if (configured && !configuring && session.autoConnect && !attempted) {
            attempted = true
            openPanel()
        }
    }

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
                Text("Abrir painel ao entrar nesta tela", Modifier.weight(1f))
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
            Text("A porta 8080 é privada. Entre no GitHub na aba do navegador se for solicitado.")
            if (channel != null) {
                Text(channel)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Link da live", channel))
                    status = "Link copiado. Cole no painel se o campo estiver vazio."
                }) { Text("Copiar link da live") }
            }
            Button(onClick = { attempted = true; openPanel() }) { Text("Abrir painel") }
            OutlinedButton(onClick = { open("https://${session.name}.github.dev/") }) {
                Text("Abrir terminal")
            }
            TextButton(onClick = { configuring = true }) { Text("Ajustes") }
        }
        if (status.isNotBlank()) Text(status)
    }
}
