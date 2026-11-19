package com.example.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.util.Locale

/**
 * Tela de navegador autenticado por Custom Tabs, SOBRE o aplicativo.
 *
 * O Google bloqueia WebView para OAuth; Custom Tabs usa o navegador real,
 * preserva a sessao e permite voltar ao editor com Voltar/Fechar.
 *
 * Isto nao cria tokens OAuth nem conecta a conta ao backend sem uma
 * integracao oficial registrada na Kick/Twitch.
 */
internal fun openOfficialStreamingBrowser(context: Context, url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
    if (uri.scheme != "https" || host !in setOf(
            "kick.com", "www.kick.com", "twitch.tv", "www.twitch.tv")) return false

    return openAuthenticatedBrowserTab(context, uri)
}

internal fun openAuthenticatedBrowserTab(context: Context, uri: Uri): Boolean {
    val candidates = listOf("com.android.chrome", "com.chrome.beta",
        "com.microsoft.emmx", "com.sec.android.app.sbrowser", "org.mozilla.firefox")
    val service = "android.support.customtabs.action.CustomTabsService"
    for (browser in candidates) {
        // Nunca lancar navegador em tela/aba separada como fallback.
        val handlesCustomTab = runCatching {
            context.packageManager.resolveService(
                Intent(service).setPackage(browser), 0) != null
        }.getOrDefault(false)
        if (!handlesCustomTab) continue

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(browser)
            addCategory(Intent.CATEGORY_BROWSABLE)
            val extras = Bundle().apply {
                // Sessao nula indica Custom Tab sem conexao preestabelecida.
                putBinder("android.support.customtabs.extra.SESSION", null)
            }
            putExtras(extras)
            putExtra("android.support.customtabs.extra.TOOLBAR_COLOR",
                Color.rgb(12, 23, 38))
            putExtra("android.support.customtabs.extra.SHOW_TITLE", true)
            putExtra("android.support.customtabs.extra.SHARE_MENU_ITEM", false)
            putExtra("androidx.browser.customtabs.extra.COLOR_SCHEME", 2)
            // Quando suportado, login desliza sobre o editor como painel alto.
            val screen = context.resources.displayMetrics.heightPixels
            putExtra("androidx.browser.customtabs.extra.INITIAL_ACTIVITY_HEIGHT_PX",
                (screen * 0.90).toInt())
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Variante instalada nao suporta esta atividade.
        } catch (_: SecurityException) {
            // Sistema bloqueou a tentativa; testar outra Custom Tab.
        }
    }
    return false
}
