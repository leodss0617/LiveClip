package com.example.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

@Composable
fun ConfiguracoesScreen(onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val connection = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    val branding = remember { context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(connection.getString("token", "").orEmpty()) }
    var pairingStatus by remember { mutableStateOf(connection.getString("pairing_status", "").orEmpty()) }
    var serverStatus by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { requestTermuxPairing(context) }
                .onFailure { pairingStatus = it.localizedMessage ?: "Termux indisponível." }
        } else pairingStatus = "Autorize Executar comandos no Termux nas permissões do aplicativo."
    }
    fun startPair() {
        pairingStatus = "Conectando ao servidor do Termux…"
        connection.edit().putString("pairing_status", pairingStatus).apply()
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED) {
            runCatching { requestTermuxPairing(context) }
                .onFailure { pairingStatus = it.localizedMessage ?: "Termux indisponível." }
        } else permission.launch("com.termux.permission.RUN_COMMAND")
    }
    DisposableEffect(connection) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "token") token = connection.getString("token", "").orEmpty()
            if (key == "pairing_status") pairingStatus = connection.getString("pairing_status", "").orEmpty()
        }
        connection.registerOnSharedPreferenceChangeListener(listener)
        onDispose { connection.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(token) {
        serverStatus = if (token.isBlank()) "Servidor ainda não conectado."
        else try {
            request("/health", token)
            "Servidor de cortes conectado e autenticado."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Falha ao conectar: " + (e.message ?: "verifique o Termux.")
        }
    }
    var logo by remember { mutableStateOf(branding.getString("logo_uri", null)?.let(Uri::parse)) }
    var logoError by remember { mutableStateOf(false) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val permission = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            logoError = permission.isFailure
            if (permission.isSuccess) {
                branding.edit().putString("logo_uri", uri.toString()).apply()
                logo = uri
            }
        }
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Configurações", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    connection.edit().putString("token", it.trim()).apply()
                },
                label = { Text("Código de conexão do Termux") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = { startPair() }, modifier = Modifier.fillMaxWidth()) {
                Text("Conectar automaticamente ao Termux")
            }
            if (pairingStatus.isNotBlank()) Text(pairingStatus, style = MaterialTheme.typography.bodySmall)
            Text(serverStatus, style = MaterialTheme.typography.bodySmall,
                color = if (serverStatus.startsWith("Servidor de cortes conectado")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Para monitorar, o servidor de cortes precisa estar ligado no Termux. " +
                "O botão acima recupera o código somente no próprio celular; não o compartilhe.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            StreamingAccountsSettings()
            HorizontalDivider()
            var chatOpacity by remember {
                mutableFloatStateOf(branding.getFloat("chat_overlay_opacity", 0.70f).coerceIn(.45f, .90f))
            }
            var chatLines by remember {
                mutableFloatStateOf(branding.getInt("chat_overlay_lines", 6).toFloat())
            }
            Text("Chat sobre a live", style = MaterialTheme.typography.titleMedium)
            Text("Fundo das mensagens: " + (chatOpacity * 100).toInt() + "%",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = chatOpacity, onValueChange = {
                chatOpacity = it
                branding.edit().putFloat("chat_overlay_opacity", it).apply()
            }, valueRange = .45f..0.90f)
            Text("Mensagens visíveis: " + chatLines.toInt(),
                style = MaterialTheme.typography.bodySmall)
            Slider(value = chatLines, onValueChange = {
                chatLines = it
                branding.edit().putInt("chat_overlay_lines", it.toInt()).apply()
            }, valueRange = 3f..10f, steps = 6)
            var useChatActivity by remember {
                mutableStateOf(branding.getBoolean("chat_signal_enabled", true))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useChatActivity, onCheckedChange = {
                    useChatActivity = it
                    branding.edit().putBoolean("chat_signal_enabled", it).apply()
                })
                Text("Analisar animação do chat", modifier = Modifier.padding(start = 10.dp))
            }
            Text("Quando o chat estiver visível e recebendo mensagens, enviar somente " +
                "contagens e intensidade agregadas ao motor de cortes. Nenhum nome ou " +
                "texto de mensagem é armazenado no Termux.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Logo do canal", style = MaterialTheme.typography.titleMedium)
            logo?.let {
                AsyncImage(it, contentDescription = "Logo salvo", modifier = Modifier.size(64.dp))
            }
            OutlinedButton(onClick = { logoPicker.launch(arrayOf("image/*")) }) {
                Text(if (logo == null) "Adicionar logo" else "Trocar logo")
            }
            if (logoError) {
                Text("Não foi possível acessar o logo. Escolha outra imagem.", color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            OutlinedButton(onClick = onOpenEditor) { Text("Editar vídeo inteiro") }
        }
    }
}
