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

@Composable
fun ConfiguracoesScreen(onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val connection = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    val branding = remember { context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(connection.getString("token", "").orEmpty()) }
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
