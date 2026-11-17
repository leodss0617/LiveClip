package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.CortaJaRepository
import com.example.domain.analyzer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.text.Normalizer
import java.util.Locale

class VideoAnalysisService : Service() {
    companion object {
        const val CHANNEL_ID = "editorclipesdss_processing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.cortaja.ACTION_START"
        const val ACTION_PAUSE = "com.example.cortaja.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.cortaja.ACTION_RESUME"
        const val ACTION_CANCEL = "com.example.cortaja.ACTION_CANCEL"
        const val ACTION_RENDER_FULL = "com.example.cortaja.ACTION_RENDER_FULL"
        const val ACTION_RENDER_QUEUE = "com.example.cortaja.ACTION_RENDER_QUEUE"
        const val EXTRA_PROJECT_ID = "extra_project_id"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_URIS = "extra_video_uris"
        const val EXTRA_LOGO_URI = "extra_logo_uri"
        const val EXTRA_FONT_ASSET = "extra_font_asset"
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val PREFS = "editorclipesdss_processing"
        const val KEY_RUNNING = "running"
        const val KEY_MESSAGE = "message"
        const val KEY_OUTPUT = "output"
        const val KEY_FAILED = "failed"
        const val KEY_QUEUE_TOTAL = "queue_total"
        const val KEY_QUEUE_INDEX = "queue_index"
        @Volatile var sharedProcessor: IncrementalVideoProcessor? = null
        @Volatile var activeProcessor: IncrementalVideoProcessor? = null

        fun startFullRender(context: Context, video: Uri, logo: Uri?, font: String, modelPath: String) =
            startFullQueue(context, listOf(video), logo, font, modelPath)

        fun startFullQueue(context: Context, videos: List<Uri>, logo: Uri?, font: String, modelPath: String) {
            require(videos.isNotEmpty() && videos.size <= 3) { "A fila aceita de 1 a 3 vídeos." }
            val i = Intent(context, VideoAnalysisService::class.java).apply {
                action = ACTION_RENDER_QUEUE
                putStringArrayListExtra(EXTRA_VIDEO_URIS, ArrayList(videos.map { it.toString() }))
                putExtra(EXTRA_LOGO_URI, logo?.toString())
                putExtra(EXTRA_FONT_ASSET, font)
                putExtra(EXTRA_MODEL_PATH, modelPath)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun startService(context: Context, projectId: Long) {
            val i = Intent(context, VideoAnalysisService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECT_ID, projectId)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun pauseService(context: Context) { context.startService(Intent(context, VideoAnalysisService::class.java).setAction(ACTION_PAUSE)) }
        fun resumeService(context: Context) { context.startService(Intent(context, VideoAnalysisService::class.java).setAction(ACTION_RESUME)) }
        fun cancelService(context: Context) { context.startService(Intent(context, VideoAnalysisService::class.java).setAction(ACTION_CANCEL)) }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private lateinit var repository: CortaJaRepository
    private var processor: IncrementalVideoProcessor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = CortaJaRepository(this)
        processor = sharedProcessor ?: IncrementalVideoProcessor(this, repository)
        activeProcessor = processor
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EditorClipesDSS::RenderWakeLock").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RENDER_FULL, ACTION_RENDER_QUEUE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparando fila", "O processamento continuará em segundo plano"))
                if (wakeLock?.isHeld != true) wakeLock?.acquire(8 * 60 * 60 * 1000L)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_RUNNING, true).putBoolean(KEY_FAILED, false)
                    .putString(KEY_MESSAGE, "Preparando fila de edição…")
                    .putInt(KEY_QUEUE_INDEX, 0).remove(KEY_OUTPUT).apply()
                val videos = intent.getStringArrayListExtra(EXTRA_VIDEO_URIS)
                    ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                    ?: intent.getStringExtra(EXTRA_VIDEO_URI)?.let { listOf(Uri.parse(it)) }
                    ?: emptyList()
                val logo = intent.getStringExtra(EXTRA_LOGO_URI)?.let(Uri::parse)
                val font = intent.getStringExtra(EXTRA_FONT_ASSET) ?: "fonts/Poppins-Black.ttf"
                val model = intent.getStringExtra(EXTRA_MODEL_PATH)
                if (videos.isEmpty() || model.isNullOrBlank()) finishFull(false, null, "Vídeos ou modelo ausente.")
                else serviceScope.launch { runFullQueue(videos.take(3), logo, font, model) }
            }
            ACTION_START -> {
                val projectId = intent.getLongExtra(EXTRA_PROJECT_ID, -1L)
                if (projectId != -1L) {
                    startForeground(NOTIFICATION_ID, buildNotification("Iniciando análise...", "Bloco 1"))
                    wakeLock?.acquire(10 * 3600 * 1000L)
                    startAnalysis(projectId)
                }
            }
            ACTION_PAUSE -> { processor?.pause(); updateNotification("Análise pausada", "Toque para continuar") }
            ACTION_RESUME -> { processor?.resume(); updateNotification("Retomando análise...", "Processando...") }
            ACTION_CANCEL -> { processor?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runFullQueue(videos: List<Uri>, logo: Uri?, font: String, model: String) {
        val completed = mutableListOf<File>()
        val failures = mutableListOf<String>()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().putInt(KEY_QUEUE_TOTAL, videos.size).apply()
        VideoHistoryManager.cleanup(this)
        videos.forEachIndexed { index, video ->
            prefs.edit().putInt(KEY_QUEUE_INDEX, index + 1).apply()
            val displayName = "Vídeo ${index + 1} de ${videos.size}"
            updateFull("$displayName: analisando áudio e imagem…", index * 100 / videos.size)
            try {
                val output = renderOne(video, logo, font, model, index, videos.size)
                VideoHistoryManager.register(this, output)
                completed += output
                val history = (prefs.getStringSet("history_outputs", emptySet()) ?: emptySet()).toMutableSet()
                history += output.absolutePath
                prefs.edit().putStringSet("history_outputs", history).putString(KEY_OUTPUT, output.absolutePath).apply()
            } catch (e: Exception) {
                failures += "$displayName: ${e.message ?: "erro desconhecido"}"
                updateNotification("$displayName falhou", "Continuando para o próximo vídeo…")
            }
        }
        val summary = "Fila concluída: ${completed.size} de ${videos.size} vídeos editados" +
            if (failures.isNotEmpty()) ". ${failures.size} falharam." else "."
        finishFull(completed.isNotEmpty(), completed.lastOrNull()?.absolutePath, summary)
    }

    private suspend fun renderOne(
        video: Uri,
        logo: Uri?,
        font: String,
        model: String,
        queueIndex: Int,
        queueSize: Int
    ): File {
        fun overall(local: Int): Int = ((queueIndex + local / 100f) * 100f / queueSize).toInt().coerceIn(0, 99)
        updateFull("Reconhecendo a fala real…", overall(5))
        val audioEnergy = mutableListOf<AudioEnergyPoint>()
        val rawSubtitles = WhisperAudioTranscriber(this).transcribe(
            video,
            model,
            onProgress = { p ->
                updateFull("Reconhecendo fala: ${(p * 100).toInt()}%", overall(5 + (p * 35).toInt()))
            },
            onAudioEnergy = { sample -> audioEnergy += sample }
        )
        updateFull("Analisando rostos, cenas e acontecimentos do vídeo…", overall(42))
        val faces = FaceTracker(this).track(video)
        val intelligence = ReactionIntelligenceAnalyzer(this).analyze(video, rawSubtitles, faces, audioEnergy)
        val subtitles = intelligence.subtitles
        val speakerTrack = FaceTrackSelector.selectSpeakerTrack(faces, audioEnergy)
        val layout = ReactionLayoutDetector().detect(faces, audioEnergy)
        val reactionSpeakerTrack = if (layout != null) {
            val region = layout.reactionContent
            val contentFaces = faces.filter { point ->
                point.centerX in region.left..region.right &&
                    point.centerY in region.top..region.bottom &&
                    (layout.streamerTrackId == null || point.trackingId != layout.streamerTrackId)
            }
            FaceTrackSelector.selectSpeakerTimeline(
                contentFaces,
                audioEnergy,
                excludedTrackingIds = layout.streamerTrackId?.let { setOf(it) } ?: emptySet()
            )
        } else emptyList()
        updateFull(
            if (layout != null) "Reação detectada: câmera em cima e vídeo embaixo…"
            else "Vídeo normal: acompanhando o rosto de quem aparece…",
            overall(52)
        )
        val renderer = FullVideoRenderer(this)
        val result = if (layout != null) {
            renderer.renderReactionVertical(
                video, layout, logo, subtitles, font, reactionSpeakerTrack
            ) { p ->
                updateFull("Renderizando: ${(p * 100).toInt()}%", overall(52 + (p * 46).toInt()))
            }
        } else {
            renderer.renderVertical(video, logo, subtitles, speakerTrack, font) { p ->
                updateFull("Renderizando: ${(p * 100).toInt()}%", overall(52 + (p * 46).toInt()))
            }
        }
        val spokenText = subtitles.joinToString(" ") { it.text }
        val baseName = buildDescriptiveFileName(video, spokenText, layout != null)
        val target = File(result.file.parentFile, "$baseName.mp4")
        val uniqueTarget = if (!target.exists()) target else File(
            target.parentFile, "${baseName}_${System.currentTimeMillis()}.mp4"
        )
        val finalFile = if (result.file.renameTo(uniqueTarget)) uniqueTarget else {
            result.file.copyTo(uniqueTarget, overwrite = false)
            result.file.delete()
            uniqueTarget
        }
        runCatching {
            val reportDir = File(getExternalFilesDir(null), "EditorClipesDSS/analises")
            if (!reportDir.exists()) reportDir.mkdirs()
            File(reportDir, finalFile.nameWithoutExtension + "_analise.txt")
                .writeText(intelligence.report, Charsets.UTF_8)
        }
        return finalFile
    }

    private fun buildDescriptiveFileName(video: Uri, spokenText: String, reaction: Boolean): String {
        val sourceName = runCatching {
            contentResolver.query(video, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().orEmpty().substringBeforeLast('.', "")
        val raw = spokenText.ifBlank { sourceName }
        val normalized = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val ignored = setOf(
            "que", "para", "com", "uma", "umas", "uns", "dos", "das", "por", "isso",
            "essa", "esse", "aquela", "aquele", "muito", "mais", "menos", "entao", "tipo",
            "assim", "voce", "voces", "ele", "ela", "eles", "elas", "the", "and", "you"
        )
        val words = Regex("[a-z0-9]{3,}").findAll(normalized).map { it.value }
            .filterNot { it in ignored }.take(6).toList()
        val topic = words.joinToString("-").ifBlank { "video-editado" }.take(48).trim('-')
        val mode = if (reaction) "reacao" else "clip"
        return "CLIPESDSS_${mode}_$topic"
    }

    private fun updateFull(message: String, percent: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MESSAGE, message).apply()
        updateNotification(message, "$percent% da fila concluído")
    }

    private fun finishFull(ok: Boolean, path: String?, message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FAILED, !ok).putString(KEY_MESSAGE, message).putString(KEY_OUTPUT, path).apply()
        updateNotification(if (ok) "Fila de edição concluída!" else "Falha na edição", message)
        stopForeground(STOP_FOREGROUND_DETACH)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        stopSelf()
    }

    private fun startAnalysis(projectId: Long) {
        serviceScope.launch {
            launch {
                processor?.progressState?.collect { p ->
                    if (p.isRunning) updateNotification("Bloco ${p.currentBlock} de ${p.totalBlocks}", "Candidatos: ${p.candidatesCount}")
                    else if (p.processedMs >= p.totalDurationMs && p.totalDurationMs > 0) {
                        updateNotification("Análise finalizada!", "Todos os blocos processados.")
                        stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
                    }
                }
            }
            processor?.processVideo(projectId)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val open = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("EditorClipesD.S.S.")
            .setSubText("Edição automática").setContentText("$title • $content")
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pending).setOngoing(true)
            .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    private fun updateNotification(title: String, content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, content))
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Processamento do EditorClipesD.S.S.", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Andamento da edição mesmo com outro aplicativo aberto."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        activeProcessor = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
