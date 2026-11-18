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
                onClick = {
                    notice = if (openOfficialStreamingBrowser(context, "https://kick.com/"))
                        "Kick aberta na tela integrada. Toque em Entrar e use Google se desejar."
                    else "Não encontrei um navegador compatível com login integrado. Verifique o Chrome."
                },
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
            "O Google bloqueia login em WebView comum. Esta tela usa uma aba segura " +
            "integrada, com o visual do editor, em vez de abrir o navegador em separado.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Importante: a autenticação é da plataforma. Para conectar as permissões " +
            "da conta ao motor de clipes ou enviar mensagens por API, " +
            "será necessário configurar a integração OAuth oficial. " +
            "Entrar aqui não transfere sua sessão automaticamente para o servidor. " +
            "O chat público continua disponível sem login.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
