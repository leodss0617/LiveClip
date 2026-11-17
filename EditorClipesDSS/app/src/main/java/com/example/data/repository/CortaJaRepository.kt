package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.entity.BlockEntity
import com.example.data.local.entity.CandidateMomentEntity
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class CortaJaRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context)
) {
    private val projectDao = database.projectDao()
    private val blockDao = database.blockDao()
    private val candidateDao = database.candidateDao()

    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    fun getProject(id: Long): Flow<ProjectEntity?> = projectDao.getProjectByIdFlow(id)

    suspend fun getProjectSync(id: Long): ProjectEntity? = withContext(Dispatchers.IO) {
        projectDao.getProjectById(id)
    }

    suspend fun getActiveOrPausedProject(): ProjectEntity? = withContext(Dispatchers.IO) {
        projectDao.getActiveOrPausedProject()
    }

    fun getBlocks(projectId: Long): Flow<List<BlockEntity>> = blockDao.getBlocksByProject(projectId)

    fun getCandidates(projectId: Long): Flow<List<CandidateMomentEntity>> =
        candidateDao.getCandidatesByProject(projectId)

    fun getTopCandidates(projectId: Long, limit: Int): Flow<List<CandidateMomentEntity>> =
        candidateDao.getTopCandidates(projectId, limit)

    suspend fun getSelectedCandidates(projectId: Long): List<CandidateMomentEntity> =
        withContext(Dispatchers.IO) {
            candidateDao.getSelectedCandidates(projectId)
        }

    suspend fun createProject(
        title: String,
        videoUri: String,
        videoDurationMs: Long,
        totalBlocks: Int,
        analysisMode: String,
        whisperModel: String
    ): Long = withContext(Dispatchers.IO) {
        val project = ProjectEntity(
            title = title,
            videoUri = videoUri,
            videoDurationMs = videoDurationMs,
            totalBlocks = totalBlocks,
            currentBlockIndex = 0,
            processedMs = 0L,
            status = "ANALYZING",
            analysisMode = analysisMode,
            whisperModel = whisperModel,
            createdAt = System.currentTimeMillis(),
            lastCheckpointMs = System.currentTimeMillis()
        )
        val projectId = projectDao.insertProject(project)

        // Initialize empty blocks for incremental processing
        val blockDurationMs = if (totalBlocks > 0) videoDurationMs / totalBlocks else videoDurationMs
        val blocks = (0 until totalBlocks).map { index ->
            val start = index * blockDurationMs
            val end = if (index == totalBlocks - 1) videoDurationMs else (index + 1) * blockDurationMs
            BlockEntity(
                projectId = projectId,
                blockIndex = index,
                startMs = start,
                endMs = end,
                isCompleted = false
            )
        }
        blockDao.insertBlocks(blocks)
        projectId
    }

    suspend fun saveCheckpoint(
        projectId: Long,
        status: String,
        processedMs: Long,
        currentBlock: Int,
        newCandidates: List<CandidateMomentEntity>,
        completedBlock: BlockEntity?
    ) = withContext(Dispatchers.IO) {
        projectDao.updateCheckpoint(
            id = projectId,
            status = status,
            processedMs = processedMs,
            currentBlock = currentBlock,
            checkpointTime = System.currentTimeMillis()
        )
        if (newCandidates.isNotEmpty()) {
            candidateDao.insertCandidates(newCandidates)
        }
        if (completedBlock != null) {
            blockDao.updateBlock(completedBlock)
        }
    }

    suspend fun updateProjectStatus(projectId: Long, status: String, errorMessage: String? = null) =
        withContext(Dispatchers.IO) {
            val project = projectDao.getProjectById(projectId)
            if (project != null) {
                projectDao.updateProject(project.copy(status = status, errorMessage = errorMessage))
            }
        }

    suspend fun toggleCandidateSelection(candidateId: Long, isSelected: Boolean) =
        withContext(Dispatchers.IO) {
            candidateDao.toggleCandidateSelection(candidateId, isSelected)
        }

    suspend fun setAllCandidatesSelected(projectId: Long, isSelected: Boolean) =
        withContext(Dispatchers.IO) {
            candidateDao.setAllCandidatesSelected(projectId, isSelected)
        }

    suspend fun deleteProject(projectId: Long) = withContext(Dispatchers.IO) {
        candidateDao.deleteCandidatesByProject(projectId)
        blockDao.deleteBlocksByProject(projectId)
        projectDao.deleteProjectById(projectId)
        cleanProjectTempFiles(projectId)
    }

    fun getTempDir(): File {
        val dir = File(context.cacheDir, "cortaja_temp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExportsDir(): File {
        val dir = File(context.filesDir, "cortaja_exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun getTempDirSizeMb(): Float = withContext(Dispatchers.IO) {
        val dir = getTempDir()
        val bytes = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        bytes / (1024f * 1024f)
    }

    suspend fun cleanAllTempFiles(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = getTempDir()
            dir.deleteRecursively()
            dir.mkdirs()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun cleanProjectTempFiles(projectId: Long) = withContext(Dispatchers.IO) {
        try {
            val dir = getTempDir()
            dir.listFiles()?.filter { it.name.startsWith("proj_${projectId}_") }?.forEach {
                it.delete()
            }
        } catch (_: Exception) {}
    }
}
