package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.BlockEntity
import com.example.data.local.entity.CandidateMomentEntity
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectByIdFlow(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE status = 'ANALYZING' OR status = 'PAUSED' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveOrPausedProject(): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET status = :status, processedMs = :processedMs, currentBlockIndex = :currentBlock, lastCheckpointMs = :checkpointTime WHERE id = :id")
    suspend fun updateCheckpoint(id: Long, status: String, processedMs: Long, currentBlock: Int, checkpointTime: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks WHERE projectId = :projectId ORDER BY blockIndex ASC")
    fun getBlocksByProject(projectId: Long): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE projectId = :projectId AND blockIndex = :index LIMIT 1")
    suspend fun getBlockByIndex(projectId: Long, index: Int): BlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: BlockEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<BlockEntity>)

    @Update
    suspend fun updateBlock(block: BlockEntity)

    @Query("DELETE FROM blocks WHERE projectId = :projectId")
    suspend fun deleteBlocksByProject(projectId: Long)
}

@Dao
interface CandidateDao {
    @Query("SELECT * FROM candidates WHERE projectId = :projectId ORDER BY score DESC, startMs ASC")
    fun getCandidatesByProject(projectId: Long): Flow<List<CandidateMomentEntity>>

    @Query("SELECT * FROM candidates WHERE projectId = :projectId ORDER BY score DESC LIMIT :limit")
    fun getTopCandidates(projectId: Long, limit: Int): Flow<List<CandidateMomentEntity>>

    @Query("SELECT * FROM candidates WHERE projectId = :projectId AND isSelectedForExport = 1 ORDER BY startMs ASC")
    suspend fun getSelectedCandidates(projectId: Long): List<CandidateMomentEntity>

    @Query("SELECT * FROM candidates WHERE id = :id")
    suspend fun getCandidateById(id: Long): CandidateMomentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidate(candidate: CandidateMomentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<CandidateMomentEntity>)

    @Update
    suspend fun updateCandidate(candidate: CandidateMomentEntity)

    @Query("UPDATE candidates SET isSelectedForExport = :isSelected WHERE id = :id")
    suspend fun toggleCandidateSelection(id: Long, isSelected: Boolean)

    @Query("UPDATE candidates SET isSelectedForExport = :isSelected WHERE projectId = :projectId")
    suspend fun setAllCandidatesSelected(projectId: Long, isSelected: Boolean)

    @Query("DELETE FROM candidates WHERE projectId = :projectId")
    suspend fun deleteCandidatesByProject(projectId: Long)
}
