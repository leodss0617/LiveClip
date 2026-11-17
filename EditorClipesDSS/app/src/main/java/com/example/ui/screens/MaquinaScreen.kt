package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ui.components.CortaJaPlayerDialog
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val BRIDGE_URL = "http://127.0.0.1:18754"

private fun connect(path: String, token: String): HttpURLConnection =
    (URL(BRIDGE_URL + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4_000
        readTimeout = if (path == "/live/player") 30_000 else 15_000
        setRequestProperty("Authorization", "Bearer ${token.trim()}")
    }

private fun HttpURLConnection.result(): JSONObject {
    val success = responseCode in 200..299
    val response = (if (success) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }
    val json = JSONObject(response ?: "{}")
    check(success) { json.optString("erro", "Falha de conexão.") }
    return json
}

internal suspend fun request(path: String, token: String, payload: JSONObject? = null) =
    withContext(Dispatchers.IO) {
        val c = connect(path, token)
        try {
            if (payload != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            c.result()
        } finally { c.disconnect() }
    }

private suspend fun upload(context: Context, video: Uri, token: String, spec: JSONObject) =
    withContext(Dispatchers.IO) {
        val c = connect("/jobs", token)
        var cached: File? = null
        try {
            val resolver = context.contentResolver
            val size = resolver.query(video, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
            val length = if (size > 0) size else {
                val copy = File.createTempFile("maquina-", ".mp4", context.cacheDir)
                cached = copy
                resolver.openInputStream(video)?.use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                } ?: error("Não consegui abrir o vídeo.")
                copy.length()
            }
            c.requestMethod = "POST"
            c.doOutput = true
            c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(length)
            c.setRequestProperty("Content-Type", "video/mp4")
            for (key in listOf("quantidade", "duracao", "efeitos", "camera", "logo", "motor")) {
                c.setRequestProperty("X-" + key, spec.get(key).toString())
            }
            val stream = cached?.inputStream() ?: resolver.openInputStream(video)
            checkNotNull(stream) { "Não consegui abrir o vídeo." }
            stream.use { input -> c.outputStream.use { input.copyTo(it, 256 * 1024) } }
            c.result()
        } finally {
            cached?.delete()
            c.disconnect()
        }
    }

internal suspend fun clipFile(context: Context, job: String, name: String, token: String): Uri =
    withContext(Dispatchers.IO) {
        require(job.matches(Regex("[a-f0-9]{32}")) && name.matches(Regex("[A-Za-z0-9_.-]+\\.mp4")))
        val directory = File(context.getExternalFilesDir(null) ?: error("Armazenamento indisponível."), "Maquina/$job")
        directory.mkdirs()
        val file = File(directory, name)
        if (!file.exists()) {
            val temp = File.createTempFile("clipe-", ".tmp", directory)
            val c = connect("/jobs/$job/$name", token)
            try {
                check(c.responseCode == 200) { "Clipe indisponível." }
                c.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(temp.length() > 0 && temp.renameTo(file)) { "Não consegui salvar a prévia." }
            } finally {
                temp.delete()
                c.disconnect()
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

internal suspend fun sendLogo(context: Context, uri: Uri, token: String): String =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "Abra o logo e escolha uma imagem válida." }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
            options.inSampleSize *= 2
        }
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Escolha o logo novamente para liberar o acesso à imagem.")
        val factor = minOf(1f, 512f / maxOf(source.width, source.height))
        val bitmap = Bitmap.createScaledBitmap(source, (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1), true)
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
        val c = connect("/logo", token)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "image/png")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            c.result().getString("id")
        } finally { c.disconnect() }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaquinaScreen(
    sharedLink: String?,
    onSharedLinkConsumed: () -> Unit,
    submission: MutableState<Boolean>,
    onOpenSettings: () -> Unit,
    onOpenLives: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val submissionScope = (context as? ComponentActivity)?.lifecycleScope ?: scope
    var reconnecting by remember { mutableStateOf(false) }
    fun startWorker(): Boolean {
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) return false
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
                "/data/data/com.termux/files/home/editorclipes-maquina/bridge/conectar.sh", "silencioso"))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return runCatching { context.startService(intent) != null }.getOrDefault(false)
    }
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWorker()
    }
    val logoPrefs = remember { context.getSharedPreferences("editorclipesdss", Context.MODE_PRIVATE) }
    var logo by remember { mutableStateOf(logoPrefs.getString("logo_uri", null)?.let(Uri::parse)) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var link by remember { mutableStateOf(prefs.getString("link_gravacao", "").orEmpty()) }
    var input by rememberSaveable { mutableStateOf<Uri?>(null) }
    val recordingUri = remember(link) {
        runCatching { URI(link.trim()) }.getOrNull()?.takeIf {
            (it.scheme.equals("https", true) || it.scheme.equals("http", true)) &&
                !it.host.isNullOrBlank() && it.userInfo == null
        }
    }
    val hasSource = input != null || recordingUri != null
    fun saveLink(value: String) {
        link = value
        prefs.edit().putString("link_gravacao", value).apply()
        input = null
    }
    LaunchedEffect(sharedLink) {
        val incoming = sharedLink?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        saveLink(incoming)
        onSharedLinkConsumed()
    }
    var count by rememberSaveable { mutableStateOf(6) }
    var maxSeconds by rememberSaveable { mutableStateOf(240) }
    var camera by rememberSaveable { mutableStateOf("auto") }
    var effects by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var submitting by submission
    var job by remember { mutableStateOf(prefs.getString("job", "") ?: "") }
    var info by remember { mutableStateOf(JSONObject()) }
    var message by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var details by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    DisposableEffect(prefs, logoPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            when (key) {
                "token" -> token = store.getString("token", "").orEmpty()
                "logo_uri" -> logo = store.getString("logo_uri", null)?.let(Uri::parse)
                "job" -> {
                    val saved = store.getString("job", "").orEmpty()
                    if (saved != job) {
                        info = JSONObject().put("status", "queued")
                        job = saved
                    }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        logoPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            logoPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    fun openClip(id: String, name: String, title: String) {
        scope.launch {
            busy = true
            try {
                preview = clipFile(context, id, name, token) to title
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Não consegui abrir o clipe."
            } finally { busy = false }
        }
    }
    fun copyClipText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EditorClipesDSS", text))
        message = "Texto copiado."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            input = uri
        }
    }
    LaunchedEffect(job, token) {
        if (job.isBlank() || token.isBlank()) return@LaunchedEffect
        var failures = 0
        while (true) {
            try {
                info = request("/jobs/$job", token)
                failures = 0
                reconnecting = false
                if (info.optString("status") in listOf("done", "error", "cancelled")) break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reconnecting = true
                if (failures++ % 5 == 0) startWorker()
            }
            delay(3000)
        }
    }
    val files = info.optJSONArray("arquivos")
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Máquina de Cortes", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(link, ::saveLink,
            label = { Text("Link da gravação") },
            trailingIcon = {
                if (link.isNotBlank()) TextButton(onClick = { saveLink("") }) { Text("Limpar") }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (link.isNotBlank() && !hasSource) {
            Text("Use um link que comece com https:// ou http://.", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }) {
                Text(if (input == null) "Escolher vídeo do celular" else "Vídeo selecionado • Trocar")
            }
            TextButton(onClick = onOpenLives) { Text("Abrir lives") }
        }
        if (hasSource) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Como serão os cortes", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quantidade de clipes", Modifier.weight(1f))
                        OutlinedButton(onClick = { count-- }, enabled = count > 1) { Text("−") }
                        Text(count.toString(), Modifier.padding(horizontal = 12.dp))
                        OutlinedButton(onClick = { count++ }, enabled = count < 30) { Text("+") }
                    }
                    Text("Duração máxima")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (minutes in 1..4) FilterChip(maxSeconds == minutes * 60,
                            onClick = { maxSeconds = minutes * 60 }, label = { Text("${minutes} min") })
                    }
                    Text("Posição da câmera no vídeo original")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in listOf("auto" to "Sem divisão", "esquerda" to "Em cima ↖", "direita" to "Em cima ↗")) {
                            FilterChip(camera == id, onClick = { camera = id }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(effects, { effects = it })
                        Text("Emojis e efeitos sonoros", Modifier.padding(start = 10.dp))
                    }
                }
            }
            if (token.isBlank()) {
                TextButton(onClick = onOpenSettings) { Text("Configurar conexão") }
            }
            Button(
                enabled = !busy && !submitting && token.isNotBlank() && hasSource
                    && info.optString("status") !in listOf("queued", "running"),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    submitting = true
                    message = ""
                    val url = if (input != null) "" else link.trim()
                    val video = input
                    val settings = JSONObject().put("quantidade", count).put("duracao", maxSeconds)
                        .put("efeitos", effects).put("camera", camera).put("motor", "pc")
                    submissionScope.launch {
                        try {
                            settings.put("logo", logo?.let { sendLogo(context, it, token) }.orEmpty())
                            val result = if (url.isNotEmpty()) request("/jobs", token, settings.put("url", url))
                            else upload(context, checkNotNull(video), token, settings)
                            info = JSONObject().put("status", "queued")
                            job = result.getString("id")
                            prefs.edit().putString("job", job).apply()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            message = if (e is java.net.ConnectException) "Abra a conexão no Termux e tente novamente."
                                      else e.message ?: "Não consegui enviar o vídeo."
                        } finally { submitting = false }
                    }
                }
            ) { Text(if (submitting) "Enviando…" else "Gerar clipes") }
        }
        val motor = when (info.optString("motor_ativo")) {
            "termux" -> "Processando no celular · Termux"
            "pc" -> "Processando no PC"
            else -> null
        }
        if (motor != null && info.optString("status") in listOf("queued", "running")) {
            Text(motor, style = MaterialTheme.typography.labelLarge)
        }
        if (info.has("fase")) Text(info.optString("fase"))
        info.optJSONObject("original")?.let { original ->
            OutlinedButton(enabled = !busy, onClick = {
                openClip(original.getString("job"), original.getString("arquivo"), "Clipe original")
            }) { Text("Comparar com o clipe original") }
        }
        if (info.optString("status") == "done" && info.optInt("prontos") < info.optInt("solicitados")) {
            Text("${info.optInt("prontos")} de ${info.optInt("solicitados")} clipes entregues.")
        }
        if (!info.isNull("erro")) Text(info.optString("erro"), color = MaterialTheme.colorScheme.error)
        if (reconnecting) {
            Text("Reconectando ao Termux. O trabalho salvo pode ser retomado.")
            TextButton(onClick = {
                if (!startWorker()) termuxPermission.launch("com.termux.permission.RUN_COMMAND")
            }) { Text("Reconectar Termux") }
        }
        if (message.isNotBlank()) Text(message)
        if (files != null) for (i in 0 until files.length()) {
            val name = files.getString(i)
            val rating = Regex("_nota-([0-9]+)-([0-9]+)\\.mp4$").find(name)
            val title = name.substringBefore("_nota-").removeSuffix(".mp4").replace('-', ' ').replace('_', ' ')
            val label = if (rating == null) title else
                "$title • Nota ${rating.groupValues[1]},${rating.groupValues[2]}/10"
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                onClick = { openClip(job, name, label) }) { Text(label) }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        details = name to request("/jobs/$job/$name/details", token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "Não consegui carregar os detalhes."
                    } finally { busy = false }
                }
            }) { Text("Nota, título e feedback") }
        }
    }
    }
    details?.let { (name, entry) ->
        val title = entry.optString("titulo")
        val caption = entry.optString("legenda")
        val reasons = entry.optJSONArray("motivos")
        val feedback = entry.optString("feedback")
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(title.ifBlank { "Detalhes do clipe" }) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isNull("nota")) Text("${entry.optString("nota").replace('.', ',')}/10")
                    if (reasons != null) for (i in 0 until reasons.length()) {
                        Text("• ${reasons.getString(i)}")
                    }
                    if (caption.isNotBlank()) Text(caption)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (title.isNotBlank()) TextButton(onClick = { copyClipText(title) }) { Text("Copiar título") }
                        if (caption.isNotBlank()) TextButton(onClick = { copyClipText(caption) }) { Text("Copiar legenda") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((vote, text) in listOf("postaria" to "Postaria", "sem_graca" to "Sem graça", "sem_contexto" to "Faltou contexto")) {
                            FilterChip(feedback == vote, enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val updated = request("/jobs/$job/$name/feedback", token, JSONObject().put("voto",vote))
                                        details = name to updated
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        message = e.message ?: "Não consegui salvar o feedback."
                                    } finally { busy = false }
                                }
                            }, label = { Text(text) })
                        }
                    }
                    if (feedback == "sem_contexto") {
                        if (!entry.optBoolean("pode_revisar")) Text("A revisão precisa do link da gravação original.")
                        else OutlinedButton(enabled = !busy && info.optString("status") !in listOf("queued","running"), onClick = {
                            submissionScope.launch {
                                busy = true
                                try {
                                    val result = request("/jobs/$job/$name/review", token, JSONObject())
                                    info = JSONObject().put("status", "queued")
                                    job = result.getString("id")
                                    prefs.edit().putString("job",job).apply()
                                    details = null
                                    message = "Revisão de contexto na fila."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = e.message ?: "Não consegui revisar o clipe."
                                } finally { busy = false }
                            }
                        }) { Text("Revisar começo e fim") }
                    }
                    if (message.isNotBlank()) Text(message)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Fechar") } }
        )
    }
    preview?.let { (uri, title) ->
        CortaJaPlayerDialog(uri, title, confirmActionText = "Compartilhar", onConfirmAction = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar clipe"))
        }, onDismiss = { preview = null })
    }
}

