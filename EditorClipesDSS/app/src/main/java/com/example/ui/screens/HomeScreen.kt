

package com.example.ui.screens

import android.net.Uri
import android.graphics.Typeface
import android.view.Gravity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import com.example.domain.analyzer.FullVideoRenderer
import com.example.domain.analyzer.FaceTracker
import com.example.domain.analyzer.ReactionLayoutDetector
import com.example.domain.analyzer.WhisperAudioTranscriber
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.ProjectEntity
import com.example.domain.model.AnalysisMode
import com.example.domain.model.WhisperModel

private val Ink = Color(0xFF0B1019)
private val Panel = Color(0xFF17212E)
private val Blue = Color(0xFF397FEC)
private val Red = Color(0xFF8EB8FF)
private val White = Color(0xFFF7FAFF)
private val Muted = Color(0xFFABB7C7)

@Composable
fun HomeScreen(
    projects: List<ProjectEntity>,
    tempSizeMb: Float,
    onCleanTempFiles: () -> Unit,
    onStartProject: (Uri, String, AnalysisMode, WhisperModel, Double?) -> Unit,
    onSelectProject: (Long) -> Unit,
    onDeleteProject: (Long) -> Unit,
    sharedVideoUri: Uri? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("editorclipesdss", android.content.Context.MODE_PRIVATE) }
    var logoUri by remember { mutableStateOf(prefs.getString("logo_uri", null)?.let(Uri::parse)) }
    var selectedFontAsset by remember { mutableStateOf(prefs.getString("subtitle_font_asset", "fonts/Poppins-Black.ttf") ?: "fonts/Poppins-Black.ttf") }
    var pickedVideo by remember { mutableStateOf<Uri?>(sharedVideoUri) }
    var busy by remember { mutableStateOf(false) }
    var renderMessage by remember { mutableStateOf<String?>(null) }
    var renderedFile by remember { mutableStateOf<java.io.File?>(null) }
    var renderFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Depois da exportação, o mesmo player passa a reproduzir o arquivo final editado.
    val previewUri = renderedFile?.let { Uri.fromFile(it) } ?: pickedVideo
    val previewPlayer = remember(previewUri) {
        previewUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }
    val modelFile = remember { java.io.File(context.filesDir, "models/ggml-base.bin") }
    var modelReady by remember {
        mutableStateOf(modelFile.exists() && modelFile.length() > 100_000_000L)
    }
    var modelImporting by remember { mutableStateOf(false) }
    var modelImportMessage by remember { mutableStateOf<String?>(null) }
    var pendingQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var queuedVideos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var historyFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var filePendingDelete by remember { mutableStateOf<java.io.File?>(null) }
    var showDeleteAllHistory by remember { mutableStateOf(false) }
    var queueTotal by remember { mutableIntStateOf(0) }
    var queueIndex by remember { mutableIntStateOf(0) }
    var showFontSettings by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(0) }

    fun processQueue(videos: List<Uri>) {
        if (busy || !modelReady || videos.isEmpty()) return
        val batch = videos.take(3)
        busy = true
        queuedVideos = batch
        pickedVideo = batch.first()
        renderMessage = "Iniciando fila com ${batch.size} vídeo(s)…"
        renderedFile = null
        renderFailed = false
        try {
            com.example.service.VideoAnalysisService.startFullQueue(
                context, batch, logoUri, selectedFontAsset, modelFile.absolutePath
            )
        } catch (e: Exception) {
            busy = false
            renderFailed = true
            renderMessage = "Não foi possível iniciar a fila: ${e.message ?: "erro desconhecido"}"
        }
    }

    LaunchedEffect(Unit) {
        com.example.service.VideoHistoryManager.cleanup(context)
        val state = context.getSharedPreferences(
            com.example.service.VideoAnalysisService.PREFS, android.content.Context.MODE_PRIVATE
        )
        while (true) {
            val running = state.getBoolean(com.example.service.VideoAnalysisService.KEY_RUNNING, false)
            val message = state.getString(com.example.service.VideoAnalysisService.KEY_MESSAGE, null)
            val output = state.getString(com.example.service.VideoAnalysisService.KEY_OUTPUT, null)
            val failed = state.getBoolean(com.example.service.VideoAnalysisService.KEY_FAILED, false)
            queueTotal = state.getInt(com.example.service.VideoAnalysisService.KEY_QUEUE_TOTAL, 0)
            queueIndex = state.getInt(com.example.service.VideoAnalysisService.KEY_QUEUE_INDEX, 0)
            busy = running
            if (message != null) renderMessage = message
            if (!running && output != null && output.isNotBlank()) renderedFile = java.io.File(output).takeIf { it.exists() }
            if (!running && failed) renderFailed = true
            historyFiles = com.example.service.VideoHistoryManager.directory(context)
                .listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
                ?.sortedByDescending { it.lastModified() }?.take(5).orEmpty()
            kotlinx.coroutines.delay(1500)
        }
    }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            logoUri = uri
            prefs.edit().putString("logo_uri", uri.toString()).apply()
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                modelImporting = true
                modelImportMessage = "Importando o modelo Whisper (142 MB)…"
                try {
                    withContext(Dispatchers.IO) {
                        modelFile.parentFile?.mkdirs()
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Não foi possível abrir o arquivo escolhido.")
                        input.use { source ->
                            modelFile.outputStream().buffered().use { destination ->
                                source.copyTo(destination, 1024 * 1024)
                            }
                        }
                        check(modelFile.length() > 100_000_000L) {
                            "O arquivo está incompleto ou não é o modelo Whisper Base."
                        }
                    }
                    modelReady = true
                    modelImportMessage = "Whisper pronto. O reconhecimento será feito no próprio aparelho."
                    Toast.makeText(context, "Modelo Whisper importado com sucesso.", Toast.LENGTH_LONG).show()
                    val waiting = pendingQueue
                    pendingQueue = emptyList()
                    if (waiting.isNotEmpty()) processQueue(waiting)
                } catch (e: Exception) {
                    modelReady = false
                    modelImportMessage = "Falha ao importar o modelo: ${e.message ?: "erro desconhecido"}"
                    Toast.makeText(context, "Não foi possível importar o modelo.", Toast.LENGTH_LONG).show()
                } finally {
                    modelImporting = false
                }
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val batch = uris.take(3)
            batch.forEach { uri ->
                try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            queuedVideos = batch
            pickedVideo = batch.first()
            if (uris.size > 3) Toast.makeText(context, "A fila aceita até 3 vídeos. Os 3 primeiros foram selecionados.", Toast.LENGTH_LONG).show()
        }
    }

    // Automatically handle a video shared from Gallery, Google Photos, or the file manager.
    // MainActivity exposes this value as Compose state so ACTION_SEND also works when app is already open.
    LaunchedEffect(sharedVideoUri) {
        val incoming = sharedVideoUri ?: return@LaunchedEffect
        pickedVideo = incoming
        if (!busy) {
            if (modelReady) {
                processQueue(listOf(incoming))
            } else {
                pendingQueue = listOf(incoming)
                Toast.makeText(context, "Vídeo recebido. Importe o modelo Whisper uma vez para começar.", Toast.LENGTH_LONG).show()
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF101925), Ink))
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28517D))
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (logoUri != null) {
                        AsyncImage(model = logoUri, contentDescription = "Logo CLIPESD.S.S.", modifier = Modifier.size(62.dp).clip(RoundedCornerShape(15.dp)))
                    } else {
                        Box(Modifier.size(62.dp).clip(RoundedCornerShape(15.dp)).background(Blue), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = White, modifier = Modifier.size(40.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("EDITOR", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("CLIPESD.S.S.", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text("Edição automática • Shorts • Reels", color = Muted, fontSize = 11.sp)
                    }
                    Surface(color = Blue.copy(alpha = 0.22f), shape = RoundedCornerShape(10.dp)) {
                        Text("9:16", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("Editar", "Biblioteca", "Ajustes").forEachIndexed { index, title ->
                    FilterChip(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        label = { Text(title, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Panel,
                            selectedContainerColor = Blue,
                            labelColor = Muted,
                            selectedLabelColor = White
                        )
                    )
                }
            }

            if (activeTab == 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF39304F))
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Red)
                        Text("EDIÇÃO AUTOMÁTICA", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(
                        "Selecione um vídeo para reconhecer a fala no próprio aparelho, gerar legendas reais e adaptar a imagem para 9:16 sem cortar a duração.",
                        color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                    )
                    if (modelReady) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF4ADE80))
                                Text("WHISPER PRONTO", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                                enabled = !modelImporting
                            ) { Text("TROCAR MODELO", color = Red, fontSize = 11.sp) }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !modelImporting,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                        ) {
                            Text(
                                if (modelImporting) "IMPORTANDO MODELO…" else "IMPORTAR MODELO WHISPER (142 MB)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    if (modelImporting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
                    }
                    modelImportMessage?.let {
                        Text(it, color = if (modelReady) Color(0xFF4ADE80) else Muted, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { if (!busy) videoPicker.launch(arrayOf("video/*")) },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                        Spacer(Modifier.width(9.dp))
                        Text("ADICIONAR ATÉ 3 VÍDEOS", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28517D))
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.QueuePlayNext, contentDescription = null, tint = Red)
                        Text("FILA DE EDIÇÃO", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${queuedVideos.size}/3", color = Red, fontWeight = FontWeight.Black)
                    }
                    Text("Escolha até 3 vídeos. O app edita um por vez e passa automaticamente para o próximo.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    queuedVideos.forEachIndexed { index, uri ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = Blue, shape = CircleShape) {
                                Text("${index + 1}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Text(uri.lastPathSegment?.substringAfterLast('/') ?: "Vídeo ${index + 1}", color = White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                    if (busy && queueTotal > 0) {
                        Text("Processando vídeo ${queueIndex.coerceAtLeast(1)} de $queueTotal", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (queuedVideos.isEmpty()) videoPicker.launch(arrayOf("video/*"))
                            else if (modelReady) processQueue(queuedVideos)
                            else {
                                pendingQueue = queuedVideos
                                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !busy && !modelImporting,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Icon(Icons.Filled.QueuePlayNext, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (queuedVideos.isEmpty()) "SELECIONAR VÍDEOS" else "INICIAR FILA (${queuedVideos.size})", fontWeight = FontWeight.ExtraBold)
                    }
                    if (queuedVideos.isNotEmpty() && !busy) {
                        TextButton(onClick = { queuedVideos = emptyList() }, modifier = Modifier.align(Alignment.End)) {
                            Text("LIMPAR FILA", color = Red)
                        }
                    }
                }
            }

            } // Aba Editar: fila e seleção
            if (activeTab == 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28517D))
            ) {
                TextButton(
                    onClick = { showFontSettings = !showFontSettings },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Red)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text("PERSONALIZAÇÃO", color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Fontes e aparência das legendas", color = Muted, fontSize = 11.sp)
                        }
                        Text(if (showFontSettings) "RECOLHER  ▴" else "ABRIR  ▾", color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (showFontSettings) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("FONTE DAS LEGENDAS", color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Toque em uma fonte para ver o exemplo e selecionar. A mesma fonte será usada na exportação.", color = Muted, fontSize = 11.sp)
                    val fontOptions = listOf(
                        "Poppins Black" to "fonts/Poppins-Black.ttf",
                        "Inter" to "fonts/Inter-Variable.ttf",
                        "Bungee" to "fonts/Bungee-Regular.ttf",
                        "Titan One" to "fonts/TitanOne-Regular.ttf",
                        "Changa One" to "fonts/ChangaOne-Regular.ttf",
                        "Bowlby One" to "fonts/BowlbyOneSC-Regular.ttf",
                        "Barlow ExtraBold" to "fonts/BarlowCondensed-ExtraBold.ttf",
                        "Anton Impacto" to "fonts/Anton-Regular.ttf",
                        "Oswald" to "fonts/Oswald-Variable.ttf",
                        "Montserrat" to "fonts/Montserrat-Variable.ttf",
                        "Bebas Neue" to "fonts/BebasNeue-Regular.ttf",
                        "Archivo Black" to "fonts/ArchivoBlack-Regular.ttf",
                        "Bangers Comic" to "fonts/Bangers-Regular.ttf",
                        "Black Ops" to "fonts/BlackOpsOne-Regular.ttf",
                        "League Spartan" to "fonts/LeagueSpartan-Variable.ttf",
                        "Lilita One" to "fonts/LilitaOne-Regular.ttf",
                        "Righteous" to "fonts/Righteous-Regular.ttf",
                        "Russo One" to "fonts/RussoOne-Regular.ttf",
                        "Teko" to "fonts/Teko-Variable.ttf",
                        "Alfa Slab" to "fonts/AlfaSlabOne-Regular.ttf"
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fontOptions.forEach { (label, asset) ->
                            FilterChip(
                                modifier = Modifier.width(150.dp).height(76.dp),
                                selected = selectedFontAsset == asset,
                                onClick = {
                                    selectedFontAsset = asset
                                    prefs.edit().putString("subtitle_font_asset", asset).apply()
                                },
                                label = {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        AndroidView(
                                            modifier = Modifier.fillMaxWidth().height(30.dp),
                                            factory = { viewContext ->
                                                android.widget.TextView(viewContext).apply {
                                                    text = "REAÇÃO! 🔥"
                                                    setTextColor(android.graphics.Color.WHITE)
                                                    textSize = 15f
                                                    gravity = Gravity.CENTER
                                                    maxLines = 1
                                                }
                                            },
                                            update = { preview ->
                                                preview.typeface = try {
                                                    Typeface.createFromAsset(context.assets, asset)
                                                } catch (_: Exception) {
                                                    Typeface.DEFAULT_BOLD
                                                }
                                                preview.text = "REAÇÃO! 🔥"
                                            }
                                        )
                                        Text(label, color = if (selectedFontAsset == asset) White else Muted, fontSize = 10.sp, maxLines = 1)
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Blue,
                                    selectedLabelColor = White
                                )
                            )
                        }
                    }
                    Text("PRÉVIA DA FONTE SELECIONADA", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        factory = { viewContext ->
                            android.widget.TextView(viewContext).apply {
                                text = "OLHA ISSO! QUE ABSURDO 🔥"
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 21f
                                gravity = Gravity.CENTER
                                maxLines = 1
                                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                            }
                        },
                        update = { preview ->
                            preview.typeface = try {
                                Typeface.createFromAsset(context.assets, selectedFontAsset)
                            } catch (_: Exception) {
                                Typeface.DEFAULT_BOLD
                            }
                            preview.text = "OLHA ISSO! QUE ABSURDO 🔥"
                        }
                    )
                }
            }
            }


            } // Aba Ajustes: fontes
            if (activeTab == 0 || activeTab == 1) {
            if (pickedVideo != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            if (renderedFile != null) "ASSISTA AO VÍDEO EDITADO" else "PRÉVIA DO VÍDEO ORIGINAL",
                            color = if (renderedFile != null) Color(0xFF4ADE80) else White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(14.dp)),
                            factory = { viewContext ->
                                PlayerView(viewContext).apply {
                                    player = previewPlayer
                                    useController = true
                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { it.player = previewPlayer }
                        )
                        Text(
                            "Toque em reproduzir para assistir ao vídeo aqui no app.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (busy || renderMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(renderMessage ?: "Processando…", color = if (renderFailed) Red else White, fontSize = 13.sp)
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
                            Text(
                                "A edição continua em segundo plano. Você pode sair do app ou abrir outro aplicativo; acompanhe o andamento pela notificação.",
                                color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                            )
                        }
                        if (renderedFile != null) {
                            Button(onClick = {
                                val fileUri = FileProvider.getUriForFile(
                                    context, context.packageName + ".fileprovider", renderedFile!!
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Compartilhar vídeo"))
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("COMPARTILHAR VÍDEO")
                            }
                        }
                    }
                }
            }

            } // Prévia e andamento
            if (activeTab == 1) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28517D))
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = Red)
                        Text("ÚLTIMOS 5 VÍDEOS", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${historyFiles.size}/5", color = Red, fontWeight = FontWeight.Bold)
                    }
                    if (historyFiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showDeleteAllHistory = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Red)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("APAGAR TODOS OS VÍDEOS")
                        }
                    }
                    Text("Cada vídeo é excluído automaticamente após 24 horas. Você também pode apagar qualquer um antes, quando quiser.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    if (historyFiles.isEmpty()) {
                        Text("Seus vídeos editados aparecerão aqui.", color = Muted, fontSize = 12.sp)
                    } else {
                        historyFiles.forEach { file ->
                            val hoursLeft = ((24L * 60L * 60L * 1000L - (System.currentTimeMillis() - file.lastModified())).coerceAtLeast(0L) / 3_600_000L)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Movie, contentDescription = null, tint = Blue)
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, color = White, fontSize = 10.sp, maxLines = 1)
                                    Text("Expira em até $hoursLeft h", color = Muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = { renderedFile = file; pickedVideo = Uri.fromFile(file); activeTab = 1 }) {
                                    Text("ASSISTIR", color = Blue, fontSize = 10.sp)
                                }
                                IconButton(onClick = { filePendingDelete = file }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Excluir vídeo", tint = Red)
                                }
                            }
                        }
                    }
                }
            }

            } // Aba Biblioteca
            if (activeTab == 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    if (logoUri != null) {
                        AsyncImage(model = logoUri, contentDescription = "Logo do canal", modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)))
                    } else {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF29243E)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = Muted)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("LOGO DO CANAL", color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(if (logoUri == null) "Cadastre uma vez para reutilizar" else "Logo salva neste aparelho", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { logoPicker.launch(arrayOf("image/*")) }) {
                        Text(if (logoUri == null) "ADICIONAR" else "TROCAR", color = Red, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureLine("9:16", "Formato vertical para Shorts e Reels")
                FeatureLine("🎙", "Legendas reais sincronizadas com Whisper local")
                FeatureLine("🖼", "Logo do canal aplicada na exportação")
                FeatureLine("⏱", "Duração completa e áudio original preservados")
                FeatureLine("✨", "Enquadramento adaptativo e imagem sem blur artificial")
            }
            } // Aba Ajustes: logo e recursos
            Spacer(Modifier.height(10.dp))
            Text("CLIPESD.S.S.  •  EDITOR DE VÍDEOS", color = Color(0xFF777187), fontSize = 10.sp, letterSpacing = 1.sp)

            filePendingDelete?.let { file ->
                AlertDialog(
                    onDismissRequest = { filePendingDelete = null },
                    title = { Text("Excluir este vídeo?") },
                    text = { Text("\"${file.name}\" será removido permanentemente da pasta do aplicativo. Essa ação não pode ser desfeita.") },
                    confirmButton = {
                        TextButton(onClick = {
                            val deleted = com.example.service.VideoHistoryManager.deleteVideo(context, file)
                            filePendingDelete = null
                            historyFiles = com.example.service.VideoHistoryManager.directory(context)
                                .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                                ?.sortedByDescending { it.lastModified() }?.take(5).orEmpty()
                            Toast.makeText(context, if (deleted) "Vídeo excluído." else "Não foi possível excluir o vídeo.", Toast.LENGTH_SHORT).show()
                        }) { Text("EXCLUIR", color = Red) }
                    },
                    dismissButton = {
                        TextButton(onClick = { filePendingDelete = null }) { Text("CANCELAR") }
                    }
                )
            }

            if (showDeleteAllHistory) {
                AlertDialog(
                    onDismissRequest = { showDeleteAllHistory = false },
                    title = { Text("Apagar todos os vídeos?") },
                    text = { Text("Todos os ${historyFiles.size} vídeos do histórico serão removidos permanentemente da pasta do aplicativo. Essa ação não pode ser desfeita.") },
                    confirmButton = {
                        TextButton(onClick = {
                            val count = com.example.service.VideoHistoryManager.deleteAll(context)
                            showDeleteAllHistory = false
                            historyFiles = emptyList()
                            renderedFile = null
                            pickedVideo = null
                            Toast.makeText(context, "$count vídeo(s) excluído(s).", Toast.LENGTH_SHORT).show()
                        }) { Text("APAGAR TODOS", color = Red) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteAllHistory = false }) { Text("CANCELAR") }
                    }
                )
            }
        }
    }
}

@Composable
private fun FeatureLine(icon: String, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(icon, color = Red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}


