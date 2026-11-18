package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Envia o MP4 usando content:// do FileProvider, sem publicar sem autorizacao.
 * TikTok abre a tela de edicao/postagem, onde a pessoa confirma a publicacao.
 */
internal fun shareClipToTikTok(context: Context, uri: Uri): Boolean {
    require(uri.scheme == "content") { "Clipe deve usar URI de compartilhamento segura." }
    val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    for (target in packages) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("clipe.mp4", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(target)
        }
        try {
            context.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(send)
            return true
        } catch (_: ActivityNotFoundException) {
            // Essa variante do TikTok nao esta instalada.
        } catch (_: SecurityException) {
            // Android negou abrir o destino: tentar outra variante.
        } catch (_: IllegalArgumentException) {
            // Variante nao instalada ou app sem autorizacao de URI.
        }
    }
    shareClipToApps(context, uri)
    return false
}

internal fun shareClipToApps(context: Context, uri: Uri) {
    require(uri.scheme == "content") { "Clipe deve usar URI de compartilhamento segura." }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("clipe.mp4", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Compartilhar clipe"))
}
