package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.components.CortaJaPlayerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class StreamClip(val job: String, val file: String, val title: String, val grade: Double?)
private data class StreamFolder(val name: String, val clips: List<StreamClip>)

@Composable
internal fun BibliotecaClipesScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("maquina_de_cortes", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(emptyList<StreamFolder>()) }
    var notice by remember { mutableStateOf("Carregando biblioteca…") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    fun clipKey(clip: StreamClip) = clip.job + "/" + clip.file

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "token") token = prefs.getString("token", "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(token, reload) {
        if (token.isBlank()) {
            notice = "Conecte o Termux em Ajustes para consultar os clipes."
            folders = emptyList()
            return@LaunchedEffect
        }
        try {
            val response = request("/library", token).optJSONArray("pastas")
            folders = (0 until (response?.length() ?: 0)).map { index ->
                val item = response!!.getJSONObject(index)
                val clipsJson = item.optJSONArray("clipes")
                val clips = (0 until (clipsJson?.length() ?: 0)).map { clipIndex ->
                    val clip = clipsJson!!.getJSONObject(clipIndex)
                    StreamClip(clip.optString("job"), clip.optString("arquivo"),
                        clip.optString("titulo"),
                        if (clip.isNull("nota")) null else clip.optDouble("nota"))
                }
                StreamFolder(item.optString("streamer"), clips)
            }
            notice = if (folders.isEmpty()) "Nenhum clipe de live encontrado." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "Não foi possível carregar: " + (e.message ?: "Confira o Termux.")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Biblioteca de clipes", style = MaterialTheme.typography.headlineSmall)
        Text("Clipes separados por streamer. Os arquivos ficam em Downloads/EditorClipesDSS/PorStreamer nas novas capturas.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { reload++ }, enabled = !loading) { Text("Atualizar") }
            OutlinedButton(onClick = {
                selecting = !selecting
                selected = emptySet()
            }) { Text(if (selecting) "Cancelar seleção" else "Selecionar clipes") }
        }
        if (selecting) {
            val eligible = folders.flatMap { it.clips }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    selected = if (selected.size == eligible.size) emptySet()
                               else eligible.map(::clipKey).toSet()
                }) { Text(if (selected.size == eligible.size && eligible.isNotEmpty()) "Desmarcar todos" else "Marcar todos") }
                Button(
                    enabled = selected.isNotEmpty() && !loading,
                    onClick = { askDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Excluir (" + selected.size + ")") }
            }
            Text("A exclusão só acontece após confirmar. Selecione os vídeos desejados.",
                 style = MaterialTheme.typography.bodySmall)
        }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        folders.forEach { folder ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = if (expanded == folder.name) null else folder.name },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(folder.name + "  •  " + folder.clips.size + " clipes")
                }
                if (expanded == folder.name) {
                    folder.clips.forEach { clip ->
                        val key = clipKey(clip)
                        val label = clip.title.take(85) +
                            (clip.grade?.let { " · nota " + String.format(java.util.Locale.US, "%.1f", it) } ?: "")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(
                                    checked = key in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + key else selected - key
                                    },
                                )
                            }
                            TextButton(
                                modifier = Modifier.weight(1f),
                                enabled = !loading,
                                onClick = {
                                    if (selecting) {
                                        selected = if (key in selected) selected - key else selected + key
                                    } else {
                                        loading = true
                                        scope.launch {
                                            try {
                                                preview = clipFile(context, clip.job, clip.file, token) to label
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                notice = "Não foi possível abrir o clipe: " + (e.message ?: "erro.")
                                            } finally { loading = false }
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(label, maxLines = 2)
                            }
                            if (!selecting) {
                                TextButton(enabled = !loading, onClick = {
                                    loading = true
                                    scope.launch {
                                        try {
                                            val video = clipFile(context, clip.job, clip.file, token)
                                            if (!shareClipToTikTok(context, video))
                                                notice = "TikTok não encontrado; escolha um aplicativo de compartilhamento."
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) {
                                            notice = "Falha ao compartilhar: " + (e.message ?: "verifique o clipe.")
                                        } finally { loading = false }
                                    }
                                }) { Text("TikTok") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { if (!loading) askDelete = false },
            title = { Text("Excluir " + selected.size + " clipes?") },
            text = { Text("Os vídeos selecionados serão apagados definitivamente do armazenamento. " +
                "Essa operação não pode ser desfeita. Outros clipes permanecerão intactos.") },
            confirmButton = {
                Button(
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val entries = folders.flatMap { it.clips }.filter { clipKey(it) in selected }
                        loading = true
                        scope.launch {
                            try {
                                val chunks = entries.chunked(100)
                                var removed = 0
                                for (chunk in chunks) {
                                    val json = org.json.JSONArray()
                                    for (clip in chunk) {
                                        json.put(JSONObject().put("job", clip.job).put("arquivo", clip.file))
                                    }
                                    val result = request("/library/delete", token, JSONObject().put("clipes", json))
                                    removed += result.optInt("excluidos")
                                }
                                notice = "$removed clipes excluídos."
                                selected = emptySet()
                                selecting = false
                                askDelete = false
                                reload++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice = "Falha ao excluir: " + (e.message ?: "verifique o Termux.")
                                reload++
                                askDelete = false
                            } finally { loading = false }
                        }
                    },
                ) { Text(if (loading) "Excluindo..." else "Excluir definitivamente") }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }, enabled = !loading) { Text("Cancelar") }
            },
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
