package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Guarda somente os cinco MP4 mais recentes e remove os que completaram 24 horas.
 * Os arquivos ficam no diretório privado externo do aplicativo.
 */
object VideoHistoryManager {
    private const val MAX_VIDEOS = 5
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L
    private const val REQUEST_CODE = 7351

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null), "EditorClipesDSS")

    @Synchronized
    fun register(context: Context, file: File) {
        if (!file.exists() || file.extension.lowercase() != "mp4") return
        cleanup(context)
        val files = directory(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_VIDEOS).forEach { it.delete() }
        scheduleNext(context)
    }

    /** Exclui manualmente um vídeo pertencente à pasta do histórico. */
    @Synchronized
    fun deleteVideo(context: Context, file: File): Boolean {
        val dir = directory(context).canonicalFile
        val target = file.canonicalFile
        if (target.parentFile != dir || !target.isFile ||
            !target.extension.equals("mp4", true)) return false
        val deleted = target.delete()
        scheduleNext(context)
        return deleted && !target.exists()
    }

    /** Exclui todos os MP4 do histórico, sem tocar em outros arquivos do app. */
    @Synchronized
    fun deleteAll(context: Context): Int {
        val dir = directory(context)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            .orEmpty()
        var deleted = 0
        files.forEach { file ->
            if (file.delete() && !file.exists()) deleted++
        }
        scheduleNext(context)
        return deleted
    }

    @Synchronized
    fun cleanup(context: Context) {
        val dir = directory(context)
        val now = System.currentTimeMillis()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.filter { now - it.lastModified() >= RETENTION_MS }.forEach { it.delete() }
        val remaining = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        remaining.drop(MAX_VIDEOS).forEach { it.delete() }
        scheduleNext(context)
    }

    @Synchronized
    private fun scheduleNext(context: Context) {
        val files = directory(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.filter { it.exists() }.orEmpty()
        val oldest = files.minByOrNull { it.lastModified() }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, VideoRetentionReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (oldest == null) {
            alarmManager.cancel(pending)
            return
        }
        val triggerAt = (oldest.lastModified() + RETENTION_MS).coerceAtLeast(System.currentTimeMillis() + 60_000L)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }
}

class VideoRetentionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        VideoHistoryManager.cleanup(context.applicationContext)
    }
}

/** Restaura a limpeza programada após reinicialização do Android. */
class VideoRetentionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            VideoHistoryManager.cleanup(context.applicationContext)
        }
    }
}
