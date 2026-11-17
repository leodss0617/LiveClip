package com.example.ui.screens

import android.content.Context
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
import com.example.cloud.CloudVideoClient
import kotlinx.coroutines.launch

@Composable
fun ConfiguracoesScreen(onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val connection = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    val branding = remember { context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(connection.getString("token", "").orEmpty()) }
    var logo by remember { mutableStateOf(branding.getString("logo_uri", null)?.let(Uri::parse)) }
    var logoError by remember { mutableStateOf(false) }
    val cloudPrefs = remember { context.getSharedPreferences("editorclipes_cloud", Context.MODE_PRIVATE) }
    var cloudUrl by remember { mutableStateOf(cloudPrefs.getString("url", "").orEmpty()) }
    // Token em memoria apenas: nao salvar segredos nas preferencias comuns.
    var cloudToken by remember { mutableStateOf("") }
    var githubPortToken by remember { mutableStateOf("") }
    var cloudStatus by remember { mutableStateOf("Nao conectado") }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudJobId by remember { mutableStateOf(cloudPrefs.getString("job_id", "").orEmpty()) }
    val scope = rememberCoroutineScope()
    fun launchCloud(action: suspend (CloudVideoClient) -> Unit) {
        if (cloudBusy) return
        cloudBusy = true
        scope.launch {
            try {
                val client = CloudVideoClient(context, cloudUrl, cloudToken, githubPortToken)
                action(client)
            } catch (error: Exception) {
                cloudStatus = error.message ?: "Falha de conexao"
            } finally {
                cloudBusy = false
            }
        }
    }
    val cloudVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launchCloud { client ->
                cloudStatus = "Enviando video..."
                val id = client.submitVideo(uri)
                cloudJobId = id
                cloudPrefs.edit().putString("job_id", id).apply()
                cloudStatus = "Video enviado. Acompanhando renderizacao..."
                val final = client.waitUntilFinished(id) { cloudStatus = "Processamento: " + it }
                if (final.optString("status") == "done") {
                    cloudStatus = "Baixando MP4..."
                    client.downloadToGallery(id)
                    cloudStatus = "MP4 salvo na galeria, em Movies/EditorClipesDSS."
                } else {
                    cloudStatus = "Falha no trabalho: " + final.optString("error", "erro desconhecido")
                }
            }
        }
    }
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
            Text("GitHub Cloud (experimental)", style = MaterialTheme.typography.titleMedium)
            Text(
                "A porta Private do Codespaces exige login GitHub alem do codigo do servidor. " +
                    "Esta versao ainda nao implementa esse login; nao torne a porta Public.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = {
                    cloudUrl = it.trim()
                    cloudPrefs.edit().putString("url", cloudUrl).apply()
                },
                label = { Text("Endereco HTTPS do servidor remoto") },
                placeholder = { Text("https://...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = cloudToken,
                onValueChange = { cloudToken = it.trim() },
                label = { Text("Codigo privado do servidor (nao salvo)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = githubPortToken,
                onValueChange = { githubPortToken = it.trim() },
                label = { Text("Credencial GitHub (opcional, nao salva)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "Nunca compartilhe credenciais. A conexao com uma porta privada " +
                    "ainda depende de uma autenticacao GitHub validada.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !cloudBusy,
                    onClick = {
                        launchCloud { client ->
                            cloudStatus = "Verificando conexao..."
                            val response = client.health()
                            cloudStatus = if (response.optString("status") == "ok") {
                                "Servidor conectado com sucesso"
                            } else {
                                "Resposta inesperada do servidor"
                            }
                        }
                    },
                ) { Text("Testar nuvem") }
                OutlinedButton(
                    enabled = !cloudBusy,
                    onClick = { cloudVideoPicker.launch(arrayOf("video/mp4")) },
                ) { Text("Enviar video") }
            }
            if (cloudJobId.isNotEmpty()) {
                Text("Ultimo trabalho: " + cloudJobId.take(8) + "...", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = !cloudBusy,
                    onClick = {
                        launchCloud { client ->
                            cloudStatus = "Consultando trabalho..."
                            val job = client.jobStatus(cloudJobId)
                            if (job.optString("status") == "done") {
                                cloudStatus = "Baixando MP4..."
                                client.downloadToGallery(cloudJobId)
                                cloudStatus = "MP4 salvo na galeria"
                            } else {
                                cloudStatus = "Trabalho: " + job.optString("status")
                            }
                        }
                    },
                ) { Text("Recuperar trabalho") }
            }
            if (cloudBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(cloudStatus, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            OutlinedButton(onClick = onOpenEditor) { Text("Editar vídeo inteiro") }
        }
    }
}
