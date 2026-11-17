package com.example.domain.analyzer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.data.local.entity.BlockEntity
import com.example.data.local.entity.CandidateMomentEntity
import com.example.data.repository.CortaJaRepository
import com.example.domain.model.AnalysisMode
import com.example.domain.model.AnalysisProgress
import com.example.domain.model.ProjectStatus
import com.example.domain.model.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.max

class IncrementalVideoProcessor(
    private val context: Context,
    private val repository: CortaJaRepository
) {
    private val audioAnalyzer = AudioEnergyAnalyzer(context)
    private val speechAnalyzer = SpeechContentAnalyzer()

    private val _progressState = MutableStateFlow(AnalysisProgress())
    val progressState: StateFlow<AnalysisProgress> = _progressState.asStateFlow()

    @Volatile
    private var isPaused = false

    @Volatile
    private var isCancelled = false

    @Volatile
    private var isCurrentlyProcessing = false

    fun pause() {
        isPaused = true
        _progressState.value = _progressState.value.copy(isPaused = true, statusMessage = "Pausado pelo usuário")
    }

    fun resume() {
        isPaused = false
        _progressState.value = _progressState.value.copy(isPaused = false, statusMessage = "Retomando processamento...")
    }

    fun cancel() {
        isCancelled = true
        _progressState.value = _progressState.value.copy(isRunning = false, statusMessage = "Cancelado")
    }

    /**
     * Reads metadata without decoding the video frames.
     */
    fun readMetadata(videoUri: Uri): Pair<Long, String> {
        val retriever = MediaSourceHelper.createRetriever(context, videoUri)
            ?: return Pair(36000000L, "1920x1080") // Default fallback for streams/simulated lives (10h)

        try {
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "1920"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "1080"
            val durationMs = durationStr?.toLongOrNull() ?: 36000000L
            return Pair(durationMs, "${width}x${height}")
        } catch (e: Exception) {
            Log.w("IncrementalVideoProcessor", "Metadata extract note: ${e.message}")
            return Pair(36000000L, "1920x1080")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Core Incremental Processing Pipeline.
     * Starts or resumes from the last saved checkpoint in Room DB.
     */
    suspend fun processVideo(projectId: Long): Boolean = withContext(Dispatchers.Default) {
        synchronized(this@IncrementalVideoProcessor) {
            if (isCurrentlyProcessing) {
                Log.d("IncrementalVideoProcessor", "Project $projectId is already being processed.")
                return@withContext true
            }
            isCurrentlyProcessing = true
        }

        try {
            val project = repository.getProjectSync(projectId) ?: return@withContext false
            val uri = Uri.parse(project.videoUri)
            val mode = AnalysisMode.entries.find { it.name == project.analysisMode } ?: AnalysisMode.FAST
            val whisperModel = WhisperModel.entries.find { it.name == project.whisperModel } ?: WhisperModel.TINY

            isPaused = false
            isCancelled = false

            val totalDurationMs = project.videoDurationMs
            val totalBlocks = project.totalBlocks
            val startIndex = project.currentBlockIndex
            var currentProcessedMs = project.processedMs

            _progressState.value = AnalysisProgress(
                projectId = projectId,
                totalDurationMs = totalDurationMs,
                processedMs = currentProcessedMs,
                currentBlock = startIndex + 1,
                totalBlocks = totalBlocks,
                candidatesCount = 0,
                isRunning = true,
                isPaused = false,
                statusMessage = "Iniciando análise incremental..."
            )

            repository.updateProjectStatus(projectId, ProjectStatus.ANALYZING.name)

            val blockDurationMs = if (totalBlocks > 0) totalDurationMs / totalBlocks else totalDurationMs
            var totalCandidatesFound = 0

            for (blockIdx in startIndex until totalBlocks) {
                if (isCancelled) {
                    repository.updateProjectStatus(projectId, ProjectStatus.IDLE.name)
                    return@withContext false
                }

                // Checkpoint check: if user paused, persist state and wait
                while (isPaused) {
                    repository.updateProjectStatus(projectId, ProjectStatus.PAUSED.name)
                    delay(500)
                    if (isCancelled) return@withContext false
                }

                val blockStartMs = blockIdx * blockDurationMs
                val blockEndMs = if (blockIdx == totalBlocks - 1) totalDurationMs else (blockIdx + 1) * blockDurationMs

                _progressState.value = _progressState.value.copy(
                    currentBlock = blockIdx + 1,
                    statusMessage = "Analisando Bloco ${blockIdx + 1} de $totalBlocks..."
                )

                // 1. Analyze Audio Energy of this block incrementally
                val blockAnalysis = audioAnalyzer.analyzeBlock(
                    videoUri = uri,
                    blockIndex = blockIdx,
                    startMs = blockStartMs,
                    endMs = blockEndMs
                ) { chunkProg, currentPeak ->
                    val blockProgressMs = (chunkProg * (blockEndMs - blockStartMs)).toLong()
                    val totalLiveMs = blockStartMs + blockProgressMs
                    _progressState.value = _progressState.value.copy(
                        processedMs = totalLiveMs,
                        currentPeakEnergy = currentPeak,
                        audioWaveformSample = (_progressState.value.audioWaveformSample + currentPeak).takeLast(40)
                    )
                }

                // 2. Full Mode Speech Transcription & Semantic Analysis
                val blockCandidates = mutableListOf<CandidateMomentEntity>()
                for (raw in blockAnalysis.candidates) {
                    if (mode == AnalysisMode.FULL) {
                        val speechResult = speechAnalyzer.analyzeSpeechSegment(
                            startMs = raw.startMs,
                            endMs = raw.endMs,
                            rawCategory = raw.categorySuggested,
                            whisperModel = whisperModel
                        )
                        val finalScore = (raw.score + speechResult.semanticScoreBonus).coerceIn(60, 99)
                        blockCandidates.add(
                            CandidateMomentEntity(
                                projectId = projectId,
                                blockIndex = blockIdx,
                                startMs = raw.startMs,
                                endMs = raw.endMs,
                                durationMs = raw.endMs - raw.startMs,
                                score = finalScore,
                                category = speechResult.detectedCategory,
                                reason = speechResult.selectionReason,
                                transcription = speechResult.transcriptionText,
                                keywords = speechResult.highlightedKeywords.joinToString(", ")
                            )
                        )
                    } else {
                        blockCandidates.add(
                            CandidateMomentEntity(
                                projectId = projectId,
                                blockIndex = blockIdx,
                                startMs = raw.startMs,
                                endMs = raw.endMs,
                                durationMs = raw.endMs - raw.startMs,
                                score = raw.score,
                                category = raw.categorySuggested,
                                reason = raw.detectionReason,
                                transcription = "Análise sonora: ${raw.detectionReason}"
                            )
                        )
                    }
                }

                totalCandidatesFound += blockCandidates.size
                currentProcessedMs = blockEndMs

                // 3. CHECKPOINT: Save progress to Room DB
                val completedBlock = BlockEntity(
                    projectId = projectId,
                    blockIndex = blockIdx,
                    startMs = blockStartMs,
                    endMs = blockEndMs,
                    isCompleted = true,
                    candidatesCount = blockCandidates.size,
                    peakVolume = blockAnalysis.peakVolume,
                    voiceActivityScore = blockAnalysis.averageVolume
                )

                val nextBlockIdx = blockIdx + 1
                repository.saveCheckpoint(
                    projectId = projectId,
                    status = if (nextBlockIdx >= totalBlocks) ProjectStatus.COMPLETED.name else ProjectStatus.ANALYZING.name,
                    processedMs = currentProcessedMs,
                    currentBlock = nextBlockIdx,
                    newCandidates = blockCandidates,
                    completedBlock = completedBlock
                )

                // 4. STORAGE: Remove block temporary caches immediately
                repository.cleanProjectTempFiles(projectId)

                _progressState.value = _progressState.value.copy(
                    processedMs = currentProcessedMs,
                    candidatesCount = totalCandidatesFound,
                    statusMessage = "Bloco ${blockIdx + 1} salvo com sucesso!"
                )
            }

            // Mark project completed
            repository.updateProjectStatus(projectId, ProjectStatus.COMPLETED.name)
            _progressState.value = _progressState.value.copy(
                isRunning = false,
                processedMs = totalDurationMs,
                statusMessage = "Análise concluída com sucesso!"
            )
            true
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e("IncrementalVideoProcessor", "Processing failed: ${e.message}", e)
                repository.updateProjectStatus(projectId, ProjectStatus.ERROR.name, e.message)
                _progressState.value = _progressState.value.copy(
                    isRunning = false,
                    statusMessage = "Erro no processamento: ${e.message}"
                )
            }
            false
        } finally {
            synchronized(this@IncrementalVideoProcessor) {
                isCurrentlyProcessing = false
            }
        }
    }
}
