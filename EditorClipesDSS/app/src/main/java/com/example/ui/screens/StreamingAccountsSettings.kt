package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Login visualmente integrado ao EditorClipesDSS por Custom Tabs seguras.
 * Nao usa WebView para Google nem captura senha, cookie ou token.
 */
@Composable
internal fun StreamingAccountsSettings() {
    val context = LocalContext.current
    var kickLogin by remember { mutableStateOf(false) }
    if (kickLogin) KickLoginDialog(onDismiss = { kickLogin = false })
    var notice by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Contas de lives", style = MaterialTheme.typography.titleMedium)
        Text(
            "Entre na Kick ou Twitch sem sair do fluxo do EditorClipesDSS. " +
            "A tela segura de autenticação aparece sobre o editor. " +
            "Ao terminar, use Voltar ou Fechar para retornar.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { kickLogin = true },
                modifier = Modifier.weight(1f)
            ) { Text("Entrar na Kick") }
            OutlinedButton(
                onClick = {
                    notice = if (openOfficialStreamingBrowser(context, "https://www.twitch.tv/login"))
                        "Twitch aberta na tela integrada. Conclua o login e volte ao editor."
                    else "Não encontrei um navegador compatível com login integrado. Verifique o Chrome."
                },
                modifier = Modifier.weight(1f)
            ) { Text("Entrar na Twitch") }
        }
        if (notice.isNotBlank()) {
            Text(notice, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "O login por e-mail abre aqui e é usado no chat oficial da Kick. " +
            "Se você entrar com Google, abra o chat pela mesma aba segura do navegador.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
