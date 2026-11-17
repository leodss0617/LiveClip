package com.example.cloud

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente REST do EditorClipesDSS Cloud.
 * A porta privada do Codespaces exige autenticacao GitHub adicional:
 * um token do servidor NAO substitui essa autenticacao.
 */
class CloudVideoClient(
    private val context: Context,
    endpoint: String,
    private val token: String,
) {
    private val root = endpoint.trim().trimEnd('/')

    init {
        require(root.startsWith("https://", ignoreCase = true)) {
            "Use somente endereco HTTPS para proteger o codigo de acesso."
        }
        require(token.isNotBlank()) { "Informe o codigo privado do servidor." }
    }

    private fun connect(path: String, method: String = "GET"): HttpURLConnection {
        require(path.startsWith("/"))
        val connection = URL(root + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        if (status !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }
            throw IOException("HTTP " + status + ": " + detail.orEmpty())
        }
        if (!connection.contentType.orEmpty().lowercase().contains("application/json")) {
            throw IOException("O endereco pode exigir login adicional no GitHub Codespaces.")
        }
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        val connection = connect("/health")
        try { readJson(connection) } finally { connection.disconnect() }
    }

    suspend fun submitVideo(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
        require(size in 1..(100L * 1024 * 1024)) {
            "O video deve ter ate 100 MB e tamanho conhecido."
        }
        val connection = connect("/jobs", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "video/mp4")
        connection.setFixedLengthStreamingMode(size)
        try {
            resolver.openInputStream(uri)?.use { input ->
                connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IOException("Nao foi possivel abrir o video.")
            readJson(connection).getString("id")
        } finally { connection.disconnect() }
    }

    suspend fun jobStatus(id: String): JSONObject = withContext(Dispatchers.IO) {
        validateId(id)
        val connection = connect("/jobs/" + id)
        try { readJson(connection) } finally { connection.disconnect() }
    }

    suspend fun waitUntilFinished(id: String, onStatus: (String) -> Unit): JSONObject {
        repeat(120) {
            val result = jobStatus(id)
            val status = result.optString("status", "unknown")
            onStatus(status)
            if (status == "done" || status == "error") return result
            delay(3000)
        }
        throw IOException("Tempo de espera excedido; trabalho " + id)
    }

    suspend fun downloadToGallery(id: String): Uri = withContext(Dispatchers.IO) {
        validateId(id)
        val connection = connect("/jobs/" + id + "/output")
        try {
            if (connection.responseCode != 200 || !connection.contentType.orEmpty().contains("video/mp4")) {
                throw IOException("MP4 indisponivel (HTTP " + connection.responseCode + ").")
            }
            val info = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "EditorClipesDSS_" + id + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EditorClipesDSS")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, info)
                ?: throw IOException("Nao foi possivel criar arquivo na galeria.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    connection.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("Falha ao salvar MP4.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            uri
        } finally { connection.disconnect() }
    }

    private fun validateId(id: String) {
        require(id.length == 32 && id.all { it in "0123456789abcdef" }) {
            "Identificador do trabalho invalido."
        }
    }
}
