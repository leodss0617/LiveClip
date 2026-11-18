package com.example.ui.screens

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        if (intent.getBooleanExtra("pair_bridge", false)) {
            val raw = result.getString("stdout").orEmpty().trim()
            val state = context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE)
            val valid = result.getInt("exitCode", -1) == 0 &&
                raw.matches(Regex("[A-Za-z0-9_-]{24,160}"))
            if (valid) {
                state.edit().putString("token", raw).putString("pairing_status", "Código recebido do Termux. Verificando servidor…").apply()
            } else {
                state.edit().putString("pairing_status", "Não foi possível obter o código. Confira a permissão Executar comandos do Termux.").apply()
            }
            return
        }
        val settings = context.getSharedPreferences("termux_console", Context.MODE_PRIVATE)
        val output = listOfNotNull(result.getString("stdout"), result.getString("stderr"),
            result.getString("errmsg")).filter { it.isNotBlank() }.joinToString("\n").takeLast(20_000)
        val folder = if (intent.getBooleanExtra("folder", false) && result.getInt("exitCode", -1) == 0)
            output.lineSequence().firstOrNull()?.takeIf { it.startsWith("/") } else null
        settings.edit().putString("output", "Saída ${result.getInt("exitCode", -1)}\n$output").apply {
            if (folder != null) putString("directory", folder)
        }.apply()
    }
}

internal fun requestTermuxPairing(context: Context) {
    val home = "/data/data/com.termux/files/home"
    val callback = Intent(context, TermuxResultReceiver::class.java).putExtra("pair_bridge", true)
    val flags = PendingIntent.FLAG_ONE_SHOT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    val result = PendingIntent.getBroadcast(context, System.nanoTime().toInt(), callback, flags)
    // Somente o callback do próprio aplicativo recebe a saída. Não guardar em logs.
    val intent = Intent("com.termux.RUN_COMMAND")
        .setClassName("com.termux", "com.termux.app.RunCommandService")
        .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
            "-lc", "proot-distro login maquina-cortes -- /bin/cat /root/.editorclipes-token"
        ))
        .putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
    checkNotNull(context.startService(intent)) { "Serviço Termux indisponível." }
}

private fun runTermux(context: Context, directory: String, args: Array<String>, openFolder: Boolean) {
    val callback = Intent(context, TermuxResultReceiver::class.java)
        .putExtra("folder", openFolder)
    val flags = PendingIntent.FLAG_ONE_SHOT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    val result = PendingIntent.getBroadcast(context, System.nanoTime().toInt(), callback, flags)
    val intent = Intent("com.termux.RUN_COMMAND")
        .setClassName("com.termux", "com.termux.app.RunCommandService")
        .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        .putExtra("com.termux.RUN_COMMAND_WORKDIR", directory)
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
    checkNotNull(context.startService(intent)) { "Termux indisponível." }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxScreen(selectedJob: String? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("termux_console", Context.MODE_PRIVATE) }
    val machine = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    var directory by remember { mutableStateOf(prefs.getString("directory", TERMUX_HOME) ?: TERMUX_HOME) }
    var path by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf(prefs.getString("output", "").orEmpty()) }
    var log by remember { mutableStateOf("") }
    var speech by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechAvailable by remember { mutableStateOf(false) }
    var speechRequested by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf("") }
    DisposableEffect(speechRequested) {
        if (!speechRequested) return@DisposableEffect onDispose {}
        val engine = TextToSpeech(context.applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) speechAvailable = true
            else speechError = "Não consegui iniciar a voz do Android."
        }
        speech = engine
        onDispose { engine.shutdown() }
    }
    var activity by remember { mutableStateOf(JSONObject()) }
    var showStop by remember { mutableStateOf(false) }
    var stopSent by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    val logScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var console by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) output = "Autorize Executar comandos no Termux nas permissões do EditorClipesDSS."
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "directory") directory = prefs.getString("directory", TERMUX_HOME) ?: TERMUX_HOME
            if (key == "output") output = prefs.getString("output", "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(console, selectedJob) {
        while (!console) {
            val token = machine.getString("token", "").orEmpty()
            val id = selectedJob ?: machine.getString("job", "").orEmpty()
            log = if (id.isBlank() || token.isBlank()) "Os registros aparecem depois de iniciar um trabalho na Máquina de Cortes."
            else try {
                activity = request("/jobs/$id/log", token)
                activity.optString("log").ifBlank { "Aguardando saída do trabalho." }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                  activity = JSONObject()
                  "Conecte a Máquina de Cortes ao Termux para carregar os registros."
              }
            delay(1800)
        }
    }
    LaunchedEffect(follow, log, logScroll.maxValue, console) {
        if (follow && !console && logScroll.maxValue != Int.MAX_VALUE) {
            logScroll.scrollTo(logScroll.maxValue)
        }
    }
    val phase = activity.optString("fase")
    val status = activity.optString("status")
    val active = status == "queued" || status == "running"
    val seconds = activity.optLong("eta_segundos", -1)
    val percent = activity.optDouble("progresso", Double.NaN)
    val done = activity.optInt("prontos")
    val total = activity.optInt("total")
    val rawLog = log.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
    val gpuWarning = rawLog.contains("GPU device discovery failed") && rawLog.contains("/sys/class/drm")
    val visibleLog = rawLog.lineSequence().filterNot {
        it.contains("GPU device discovery failed") && it.contains("/sys/class/drm")
    }.joinToString("\n")
    val downloading = status == "running" && phase.startsWith("Baixando")
    val onPc = phase.endsWith(" no PC")
    val device = if (onPc) "O PC" else "O Termux"
    val downloadStage = remember(downloading, rawLog) {
        if (downloading) LiveDownloadStage.fromLog(rawLog) else LiveDownloadStage.DOWNLOADING
    }
    val stageTitle = when (downloadStage) {
        LiveDownloadStage.ASSEMBLING -> if (onPc) "Finalizando os fragmentos no PC" else "Finalizando os fragmentos"
        LiveDownloadStage.FINALIZING -> if (onPc) "Preparando o vídeo baixado no PC" else "Preparando o vídeo baixado"
        else -> phase
    }
    val showPercent = percent.isFinite() && downloadStage == LiveDownloadStage.DOWNLOADING
    val explanation = when {
        status == "error" -> "O trabalho encontrou uma falha. Você pode tentar novamente."
        status == "done" -> "Processamento concluído. $done de $total clipes prontos."
        status == "cancelled" -> "O processamento foi parado."
        activity.optBoolean("parando") -> "Parando o processamento."
        status == "queued" -> "O trabalho está na fila."
        phase.startsWith("Transcrevendo") -> {
            val progress = if (percent.isFinite()) " Transcrição em ${percent.toInt()} por cento." else ""
            val estimate = if (seconds > 0) " Restam cerca de ${(seconds + 59) / 60} minutos nesta etapa." else ""
            "$device está transformando o áudio em texto para encontrar os momentos da live.$progress$estimate $done de $total clipes prontos."
        }
        downloading -> when (downloadStage) {
            LiveDownloadStage.ASSEMBLING -> "$device está concluindo e juntando os fragmentos da live."
            LiveDownloadStage.FINALIZING -> "$device concluiu o download e está preparando o arquivo para análise."
            else -> "$device está baixando a gravação da live."
        }
        phase.startsWith("Verificando") -> "$device está conferindo se o vídeo e o áudio podem ser lidos."
        phase.startsWith("Selecionando") -> "$device está escolhendo trechos com começo claro e um desfecho."
        phase.startsWith("Recebendo") -> "O celular está recebendo os clipes prontos do PC."
        phase.startsWith("Exportando") -> "$phase. $done de $total clipes prontos."
        phase.isNotBlank() -> phase
        else -> "Aguardando um trabalho ou a conexão com o Termux."
    }
    LaunchedEffect(speechAvailable, speechRequested) {
        if (speechAvailable && speechRequested) {
            val engine = speech ?: return@LaunchedEffect
            if (engine.setLanguage(Locale.forLanguageTag("pt-BR")) < 0) {
                speechError = "Instale a voz Português (Brasil) nas configurações do Android."
            } else {
                engine.speak(explanation, TextToSpeech.QUEUE_FLUSH, null, "editor-status")
            }
        }
    }
    if (showStop) AlertDialog(
        onDismissRequest = { showStop = false },
        title = { Text("Parar processamento?") },
        text = { Text("Os clipes já exportados ficarão em Downloads.") },
        confirmButton = {
            TextButton(onClick = {
                showStop = false
                stopSent = true
                stopError = ""
                scope.launch {
                    try {
                        val id = activity.getString("id")
                        request("/jobs/$id/stop", machine.getString("token", "").orEmpty(), JSONObject())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stopError = e.message ?: "Falha ao solicitar a parada."
                    } finally { stopSent = false }
                }
            }) { Text("Parar") }
        },
        dismissButton = { TextButton(onClick = { showStop = false }) { Text("Continuar") } }
    )
    fun execute(args: Array<String>, folder: Boolean = false) {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(PERMISSION)
            return
        }
        try {
            output = "Executando no Termux…"
            runTermux(context, directory, args, folder)
        } catch (e: Exception) {
            output = e.localizedMessage ?: "Não foi possível enviar o comando."
        }
    }
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()
            .then(if (console) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Termux", style = MaterialTheme.typography.headlineMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!console, { console = false }, label = { Text("Atividade dos clipes") })
                FilterChip(console, { console = true }, label = { Text("Comandos e pastas") })
            }
            if (!console) {
                if (stageTitle.isNotBlank()) Text(stageTitle, style = MaterialTheme.typography.titleMedium)
                Text(explanation)
                if (gpuWarning && active && phase.startsWith("Transcrevendo")) {
                    Text("A verificação da GPU foi bloqueada pelo Android. A transcrição usa o processador (CPU).")
                }
                if (active && showPercent && !activity.optBoolean("parando")) {
                    LinearProgressIndicator(progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                }
                if (active) {
                    val stopping = activity.optBoolean("parando")
                    val time = when {
                        stopping -> "Parando…"
                        downloadStage != LiveDownloadStage.DOWNLOADING -> "Aguardando a preparação do arquivo"
                        downloading && seconds == 0L -> "Concluindo o download"
                        seconds < 0 -> "Sem estimativa para esta etapa"
                        else -> {
                            val minutes = (seconds + 59) / 60
                            val formatted = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
                            val stage = if (phase.startsWith("Transcrevendo")) "na transcrição" else "no download"
                            "Restam cerca de $formatted $stage"
                        }
                    }
                    Text(if (showPercent && !stopping) "${percent.toInt()}% • $time" else time)
                    Text("${activity.optInt("prontos")} de ${activity.optInt("total")} clipes prontos")
                }
                if (!activity.isNull("erro")) Text(activity.optString("erro"), color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        speechError = ""
                        if (!speechRequested) speechRequested = true
                        else if (speechAvailable) {
                            val engine = speech
                            if (engine != null && engine.setLanguage(Locale.forLanguageTag("pt-BR")) >= 0) {
                                engine.speak(explanation, TextToSpeech.QUEUE_FLUSH, null, "editor-status")
                            } else speechError = "Instale a voz Português (Brasil) nas configurações do Android."
                        } else speechError = "A voz do Android ainda não está disponível."
                    }) { Text("Ouvir status") }
                    FilterChip(follow, { follow = !follow }, label = { Text("Seguir final") })
                    if (status == "error") Button(enabled = !retrying, onClick = {
                        retrying = true
                        stopError = ""
                        val id = activity.optString("id")
                        scope.launch {
                            try {
                                request("/jobs/$id/retry", machine.getString("token", "").orEmpty(), JSONObject())
                                if (activity.optString("id") == id && activity.optString("status") == "error") {
                                    activity = JSONObject(activity.toString())
                                        .put("status", "queued").put("fase", "Nova tentativa na fila")
                                        .put("erro", JSONObject.NULL)
                                }
                                follow = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                stopError = e.message ?: "Não consegui tentar novamente."
                            } finally { retrying = false }
                        }
                    }) { Text(if (retrying) "Reenfileirando…" else "Tentar novamente") }
                    if (active) OutlinedButton(enabled = !stopSent && !activity.optBoolean("parando"),
                        onClick = { showStop = true }) { Text("Parar processo") }
                }
                if (speechError.isNotBlank()) Text(speechError, color = MaterialTheme.colorScheme.error)
                if (stopError.isNotBlank()) Text(stopError, color = MaterialTheme.colorScheme.error)
                Surface(modifier = Modifier.weight(1f).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.verticalScroll(logScroll).padding(12.dp)) {
                        SelectionContainer { Text(visibleLog, fontFamily = FontFamily.Monospace) }
                    }
                }
            } else {
                Text("Pasta atual")
                SelectionContainer { Text(directory, fontFamily = FontFamily.Monospace) }
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Caminho da pasta") }, placeholder = { Text("~/storage/downloads") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val target = path.trim().ifBlank { "." }.let {
                            if (it == "~") TERMUX_HOME else if (it.startsWith("~/")) TERMUX_HOME + it.drop(1) else it
                        }
                        execute(arrayOf("-c", "cd -- \"\$1\" && pwd -P && ls -lah", "abrir", target), true)
                    }) { Text("Abrir pasta") }
                    OutlinedButton(onClick = { path = ".."; execute(arrayOf("-c", "cd .. && pwd -P && ls -lah"), true) }) {
                        Text("Pasta acima")
                    }
                }
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), maxLines = 4,
                    label = { Text("Comando") }, placeholder = { Text("pwd ou ls -lah") })
                Button(enabled = command.isNotBlank(), onClick = {
                    execute(arrayOf("-c", command))
                }, modifier = Modifier.fillMaxWidth()) { Text("Executar comando") }
                SelectionContainer { Text(output.ifBlank { "A saída aparece aqui quando o comando termina." },
                    fontFamily = FontFamily.Monospace) }
                Text("Para habilitar, permita executar comandos nas permissões do app e habilite allow-external-apps no Termux.")
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("Permissões do app") }
            }
        }
    }
}

