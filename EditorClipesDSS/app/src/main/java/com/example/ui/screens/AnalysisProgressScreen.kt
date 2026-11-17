package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ProjectEntity
import com.example.domain.model.AnalysisProgress
import com.example.ui.components.AudioWaveformVisualizer
import com.example.ui.components.MetricTile
import com.example.ui.theme.BgDark
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.BrandCyan
import com.example.ui.theme.BrandPrimary
import com.example.ui.theme.BrandPrimaryLight
import com.example.ui.theme.BrandSecondary
import com.example.ui.theme.GreenSuccess
import com.example.ui.theme.RedError
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AnalysisProgressScreen(
    project: ProjectEntity,
    progress: AnalysisProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onViewResults: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Real mathematical calculations based on exact timestamps
    val totalDurationFormatted = formatLiveDuration(project.videoDurationMs)
    val processedFormatted = formatLiveDuration(progress.processedMs)
    val remainingFormatted = "${formatLiveDuration(progress.remainingMs)} aproximadamente"
    val blockText = "${progress.currentBlock}/${progress.totalBlocks}"
    val candidatesCountText = "${progress.candidatesCount}"

    val isCompleted = progress.processedMs >= project.videoDurationMs && project.videoDurationMs > 0
    val isPaused = progress.isPaused || project.status == "PAUSED"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Bar: Project title & Back
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Modo: ${project.analysisMode} • Modelo: ${project.whisperModel}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            OutlinedButton(
                onClick = onBack,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Fechar", color = TextSecondary, fontSize = 12.sp)
            }
        }

        // Live Processing Monitor (Requested Exact Specification)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = SurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandPrimary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "MONITOR INCREMENTAL DA LIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandPrimaryLight,
                        letterSpacing = 1.sp
                    )

                    // Status Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isCompleted -> GreenSuccess
                                        isPaused -> BrandAmber
                                        else -> BrandSecondary
                                    }
                                )
                        )
                        Text(
                            text = when {
                                isCompleted -> "FINALIZADO"
                                isPaused -> "PAUSADO"
                                else -> "PROCESSANDO"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isCompleted -> GreenSuccess
                                isPaused -> BrandAmber
                                else -> BrandSecondary
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mandatory Items: Duração, Processado, Restante, Bloco, Candidatos encontrados
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveMetricRow(label = "Duração", value = totalDurationFormatted, color = TextPrimary)
                    LiveMetricRow(label = "Processado", value = processedFormatted, color = BrandCyan)
                    LiveMetricRow(label = "Restante", value = remainingFormatted, color = BrandAmber)
                    LiveMetricRow(label = "Bloco", value = blockText, color = BrandPrimaryLight)
                    LiveMetricRow(label = "Candidatos encontrados", value = candidatesCountText, color = GreenSuccess)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Real progress bar based on exact milliseconds
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = BrandPrimaryLight,
                    trackColor = SurfaceDark
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = progress.statusMessage,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "${(progress.progressFraction * 100).toInt()}% real",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }
        }

        // Live Audio Activity Waveform
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "ATIVIDADE SONORA / ENERGIA DO ÁUDIO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                AudioWaveformVisualizer(
                    samples = progress.audioWaveformSample,
                    currentPeak = progress.currentPeakEnergy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                )
            }
        }

        // Action Buttons: PAUSAR, CONTINUAR, CANCELAR
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isCompleted) {
                    Button(
                        onClick = onViewResults,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenSuccess),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_view_results_completed")
                    ) {
                        Text(
                            text = "VER MELHORES MOMENTOS (${progress.candidatesCount})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPaused) {
                            Button(
                                onClick = onResume,
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_resume_analysis")
                            ) {
                                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CONTINUAR", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onPause,
                                colors = ButtonDefaults.buttonColors(containerColor = BrandAmber),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_pause_analysis")
                            ) {
                                Icon(imageVector = Icons.Filled.Pause, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("PAUSAR", fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }

                        Button(
                            onClick = onCancel,
                            colors = ButtonDefaults.buttonColors(containerColor = RedError.copy(alpha = 0.2f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RedError.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("btn_cancel_analysis")
                        ) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null, tint = RedError)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CANCELAR", fontWeight = FontWeight.Bold, color = RedError)
                        }
                    }

                    if (progress.candidatesCount > 0) {
                        OutlinedButton(
                            onClick = onViewResults,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("btn_preview_partial_candidates")
                        ) {
                            Text(
                                text = "Ver ${progress.candidatesCount} candidatos já encontrados →",
                                color = BrandCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Background & Checkpoint Guarantee Info
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GreenSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = GreenSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sistema de Checkpoint Ativo",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Se o Android matar o app, o progresso continuará exatamente do último bloco salvo no banco Room.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LiveMetricRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun formatLiveDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return "${String.format("%02d", hours)}h${String.format("%02d", minutes)}min"
}
