package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val videoUri: String,
    val videoDurationMs: Long,
    val totalBlocks: Int,
    val currentBlockIndex: Int = 0,
    val processedMs: Long = 0,
    val status: String = "IDLE",
    val analysisMode: String = "FAST",
    val whisperModel: String = "TINY",
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckpointMs: Long = System.currentTimeMillis(),
    val tempDirSizeMb: Float = 0f,
    val errorMessage: String? = null
)

@Entity(tableName = "blocks")
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val blockIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val isCompleted: Boolean = false,
    val candidatesCount: Int = 0,
    val peakVolume: Float = 0f,
    val voiceActivityScore: Float = 0f
)

@Entity(tableName = "candidates")
data class CandidateMomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val blockIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val score: Int,
    val category: String,
    val reason: String,
    val transcription: String = "",
    val keywords: String = "",
    val isSelectedForExport: Boolean = true,
    val exportedFilePath: String? = null,
    val thumbnailUri: String? = null
)
