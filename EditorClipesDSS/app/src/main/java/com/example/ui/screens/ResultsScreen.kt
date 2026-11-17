package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.CandidateMomentEntity
import com.example.domain.model.MomentCategory
import com.example.ui.components.CandidateMomentCard
import com.example.ui.components.CategoryBadge
import com.example.ui.components.CortaJaPlayerDialog
import com.example.ui.components.ScoreBadge
import com.example.ui.components.formatSeconds
import com.example.ui.components.formatTime
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
fun ResultsScreen(
    candidates: List<CandidateMomentEntity>,
    topLimit: Int,
    onSetTopLimit: (Int) -> Unit,
    selectedCategory: String?,
    onSetSelectedCategory: (String?) -> Unit,
    onToggleCandidate: (Long, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onCreateCuts: () -> Unit,
    onBack: () -> Unit,
    videoUri: android.net.Uri = android.net.Uri.EMPTY,
    modifier: Modifier = Modifier
) {
    var previewCandidate by remember { mutableStateOf<CandidateMomentEntity?>(null) }

    val topOptions = listOf(5, 10, 20, 999)
    val topLabels = listOf("TOP 5", "TOP 10", "TOP 20", "TODOS")

    // Filter candidates by TOP N and Category
    val filteredCandidates = remember(candidates, topLimit, selectedCategory) {
        val sorted = candidates.sortedByDescending { it.score }
        val topSlice = if (topLimit >= 999) sorted else sorted.take(topLimit)
        if (selectedCategory == null) {
            topSlice
        } else {
            topSlice.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        }
    }

    val selectedCount = candidates.count { it.isSelectedForExport }

    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Back, Title, Select All
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                text = "Melhores Momentos",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${candidates.size} candidatos detectados no total",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    TextButton(onClick = { onSelectAll(selectedCount < candidates.size) }) {
                        Text(
                            text = if (selectedCount < candidates.size) "Marcar Todos" else "Desmarcar",
                            color = BrandPrimaryLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // TOP 5 / TOP 10 / TOP 20 Selector (Mandatory Requirement)
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        topOptions.forEachIndexed { index, limit ->
                            val isSelected = (limit == topLimit)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) BrandPrimary else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSetTopLimit(limit) }
                                    .testTag("tab_top_${topLabels[index]}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = topLabels[index],
                                        color = if (isSelected) Color.Black else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Category Filter Chips
            item {
                val categoryScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(categoryScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onSetSelectedCategory(null) },
                        label = { Text("Todas Categorias") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandPrimary.copy(alpha = 0.25f),
                            selectedLabelColor = BrandPrimaryLight,
                            containerColor = SurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selectedCategory == null) BrandPrimary else SurfaceBorder,
                            enabled = true,
                            selected = selectedCategory == null
                        )
                    )

                    MomentCategory.entries.forEach { cat ->
                        val isCatSelected = (selectedCategory == cat.label)
                        val chipColor = Color(cat.iconColorHex)
                        FilterChip(
                            selected = isCatSelected,
                            onClick = { onSetSelectedCategory(if (isCatSelected) null else cat.label) },
                            label = { Text(cat.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor.copy(alpha = 0.25f),
                                selectedLabelColor = chipColor,
                                containerColor = SurfaceElevated,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (isCatSelected) chipColor else SurfaceBorder,
                                enabled = true,
                                selected = isCatSelected
                            )
                        )
                    }
                }
            }

            // Candidates List
            if (filteredCandidates.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SurfaceDark,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Nenhum momento encontrado para este filtro",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(filteredCandidates, key = { it.id }) { candidate ->
                    CandidateMomentCard(
                        candidate = candidate,
                        onToggleSelect = { isSel -> onToggleCandidate(candidate.id, isSel) },
                        onPlayPreview = { previewCandidate = candidate }
                    )
                }
            }
        }

        // Floating Bottom Action Bar: CRIAR CORTES
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = SurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "$selectedCount selecionados",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Corte 9:16 direto sem reencodar",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = onCreateCuts,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandSecondary,
                        disabledContainerColor = SurfaceDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("btn_create_cuts")
                ) {
                    Icon(imageVector = Icons.Filled.ContentCut, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CRIAR CORTES",
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Modal: Interactive 9:16 Real Video Player & Subtitles
        if (previewCandidate != null) {
            val c = previewCandidate!!
            CortaJaPlayerDialog(
                videoUri = videoUri,
                title = "${c.category} (${formatTime(c.startMs)} → ${formatTime(c.endMs)})",
                startMs = c.startMs,
                endMs = c.endMs,
                transcription = c.transcription.ifBlank { c.reason },
                confirmActionText = if (!c.isSelectedForExport) "SELECIONAR PARA CORTE 9:16" else "JÁ SELECIONADO (DESMARCAR)",
                onConfirmAction = {
                    onToggleCandidate(c.id, !c.isSelectedForExport)
                },
                onDismiss = { previewCandidate = null }
            )
        }
    }
}
