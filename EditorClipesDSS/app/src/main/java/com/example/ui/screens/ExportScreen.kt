package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.domain.analyzer.ClipExportResult
import com.example.domain.model.ExportResolution
import com.example.domain.model.ExportSettings
import com.example.domain.model.SubtitleStyle
import com.example.domain.model.TargetPlatform
import com.example.ui.components.CortaJaPlayerDialog
import com.example.ui.theme.BgDark
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.BrandCyan
import com.example.ui.theme.BrandPrimary
import com.example.ui.theme.BrandPrimaryLight
import com.example.ui.theme.BrandSecondary
import com.example.ui.theme.GreenSuccess
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ExportScreen(
    settings: ExportSettings,
    onUpdateSettings: (ExportSettings) -> Unit,
    isExporting: Boolean,
    exportProgress: Float,
    exportedClips: List<ClipExportResult>,
    onStartRendering: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playingClip by remember { mutableStateOf<ClipExportResult?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Configurar Exportação 9:16",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "TikTok, Instagram Reels e YouTube Shorts",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Platform & Aspect Ratio Tile
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "FORMATO DE VÍDEO CURTO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandPrimary.copy(alpha = 0.15f))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📱 Enquadramento 9:16 Vertical",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandPrimaryLight
                            )
                        }
                    }
                }
            }
        }

        // Resolution Selector: 1080x1920 vs 720x1280 (Lightweight)
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "RESOLUÇÃO DE SAÍDA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ExportResolution.entries.forEach { res ->
                        val isSelected = (settings.resolution == res)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SurfaceDark else Color.Transparent)
                                .clickable { onUpdateSettings(settings.copy(resolution = res)) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onUpdateSettings(settings.copy(resolution = res)) },
                                colors = RadioButtonDefaults.colors(selectedColor = BrandPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = res.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = res.description,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subtitle Settings (Timestamps, highlighted keywords, styles)
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "LEGENDAS SINCRONIZADAS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "Gerar arquivo .SRT e sobreposição nos vídeos",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.includeSubtitles,
                            onCheckedChange = { onUpdateSettings(settings.copy(includeSubtitles = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BrandPrimary,
                                checkedTrackColor = BrandPrimary.copy(alpha = 0.3f)
                            )
                        )
                    }

                    AnimatedVisibility(visible = settings.includeSubtitles) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onUpdateSettings(settings.copy(highlightKeywords = !settings.highlightKeywords))
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = settings.highlightKeywords,
                                    onCheckedChange = {
                                        onUpdateSettings(settings.copy(highlightKeywords = it))
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = BrandAmber)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = "Destacar palavras-chave",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandAmber
                                    )
                                    Text(
                                        text = "Aumenta retenção com termos em amarelo neon e ciano",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "ESTILO DA LEGENDA",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            SubtitleStyle.entries.forEach { style ->
                                val isSelected = (settings.subtitleStyle == style)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onUpdateSettings(settings.copy(subtitleStyle = style)) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(subtitleStyle = style)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = BrandCyan)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = style.title,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto Clean Temporary Storage
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Limpeza Automática de Cache",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Remove arquivos temporários da memória ao finalizar a renderização dos cortes.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = settings.autoCleanTempFiles,
                        onCheckedChange = { onUpdateSettings(settings.copy(autoCleanTempFiles = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GreenSuccess,
                            checkedTrackColor = GreenSuccess.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        // Render Action Button
        item {
            if (isExporting) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandSecondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Renderizando cortes selecionados...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandSecondary
                        )
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = BrandSecondary,
                            trackColor = SurfaceDark
                        )
                        Text(
                            text = "${(exportProgress * 100).toInt()}% concluído • Processando apenas os trechos selecionados",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                Button(
                    onClick = onStartRendering,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_start_render_cuts")
                ) {
                    Icon(imageVector = Icons.Filled.ContentCut, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RENDERIZAR E SALVAR CORTES",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }
        }

        // Exported Clips List
        if (exportedClips.isNotEmpty()) {
            item {
                Text(
                    text = "CORTES PRONTOS PARA POSTAR (${exportedClips.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenSuccess,
                    letterSpacing = 1.sp
                )
            }

            items(exportedClips) { clip ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GreenSuccess.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GreenSuccess.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = GreenSuccess,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = clip.outputFile.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Duração: ${clip.durationSeconds}s • .MP4 e .SRT",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Watch Clip Button
                            IconButton(
                                onClick = { playingClip = clip },
                                modifier = Modifier.testTag("btn_play_clip_${clip.candidateId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayCircle,
                                    contentDescription = "Assistir corte",
                                    tint = BrandCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Share action
                            IconButton(
                                onClick = {
                                    shareClipFile(context, clip)
                                },
                                modifier = Modifier.testTag("btn_share_clip_${clip.candidateId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = "Compartilhar",
                                    tint = BrandPrimaryLight
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // Modal Video Player for Exported Clips
    if (playingClip != null) {
        val clip = playingClip!!
        var srtText: String? = null
        try {
            val subFile = clip.subtitleFile
            if (subFile != null && subFile.exists() && subFile.length() > 0) {
                srtText = subFile.readLines().filter { it.isNotBlank() && !it.contains("-->") && !it.all { ch -> ch.isDigit() } }.joinToString(" ")
            }
        } catch (_: Exception) {}

        CortaJaPlayerDialog(
            videoUri = Uri.fromFile(clip.outputFile),
            title = clip.outputFile.name,
            transcription = srtText,
            confirmActionText = "COMPARTILHAR ESTE VÍDEO",
            onConfirmAction = {
                shareClipFile(context, clip)
            },
            onDismiss = { playingClip = null }
        )
    }
    }
}

private fun shareClipFile(context: Context, clip: ClipExportResult) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            clip.outputFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar Corte no TikTok / Reels / Shorts"))
    } catch (_: Exception) {
        // Fallback generic send
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Corte gerado com CortaJá: ${clip.outputFile.name}")
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar"))
    }
}
