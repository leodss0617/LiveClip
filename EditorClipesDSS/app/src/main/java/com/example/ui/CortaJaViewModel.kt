package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CandidateMomentEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.repository.CortaJaRepository
import com.example.domain.analyzer.ClipExportResult
import com.example.domain.analyzer.IncrementalVideoProcessor
import com.example.domain.analyzer.VideoClipper
import com.example.domain.model.AnalysisMode
import com.example.domain.model.AnalysisProgress
import com.example.domain.model.ExportSettings
import com.example.domain.model.ProjectStatus
import com.example.domain.model.WhisperModel
import com.example.service.VideoAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CortaJaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CortaJaRepository(application)
    private val clipper = VideoClipper(application)
    val processor = IncrementalVideoProcessor(application, repository)

    val projects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProjectId: StateFlow<Long?> = _selectedProjectId.asStateFlow()

    private val _currentProject = MutableStateFlow<ProjectEntity?>(null)
    val currentProject: StateFlow<ProjectEntity?> = _currentProject.asStateFlow()

    private val _allCandidates = MutableStateFlow<List<CandidateMomentEntity>>(emptyList())
    val allCandidates: StateFlow<List<CandidateMomentEntity>> = _allCandidates.asStateFlow()

    private val _topLimit = MutableStateFlow(10) // default TOP 10
    val topLimit: StateFlow<Int> = _topLimit.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null) // null = all
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    val analysisProgress: StateFlow<AnalysisProgress> = processor.progressState

    private val _tempDirSizeMb = MutableStateFlow(0f)
    val tempDirSizeMb: StateFlow<Float> = _tempDirSizeMb.asStateFlow()

    private val _exportSettings = MutableStateFlow(ExportSettings())
    val exportSettings: StateFlow<ExportSettings> = _exportSettings.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportedResults = MutableStateFlow<List<ClipExportResult>>(emptyList())
    val exportedResults: StateFlow<List<ClipExportResult>> = _exportedResults.asStateFlow()

    init {
        refreshTempSize()
        checkActiveProject()
    }

    private fun checkActiveProject() {
        viewModelScope.launch {
            val active = repository.getActiveOrPausedProject()
            if (active != null) {
                selectProject(active.id)
            }
        }
    }

    fun refreshTempSize() {
        viewModelScope.launch {
            _tempDirSizeMb.value = repository.getTempDirSizeMb()
        }
    }

    fun cleanTempFiles() {
        viewModelScope.launch {
            repository.cleanAllTempFiles()
            refreshTempSize()
        }
    }

    fun setTopLimit(limit: Int) {
        _topLimit.value = limit
    }

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun updateExportSettings(settings: ExportSettings) {
        _exportSettings.value = settings
    }

    fun selectProject(projectId: Long) {
        _selectedProjectId.value = projectId
        viewModelScope.launch {
            repository.getProject(projectId).collectLatest { proj ->
                _currentProject.value = proj
            }
        }
        viewModelScope.launch {
            repository.getCandidates(projectId).collectLatest { list ->
                _allCandidates.value = list
            }
        }
    }

    fun startNewAnalysis(
        videoUri: Uri,
        title: String,
        mode: AnalysisMode,
        whisperModel: WhisperModel,
        forcedDurationHours: Double? = null
    ) {
        viewModelScope.launch {
            val meta = processor.readMetadata(videoUri)
            val durationMs = forcedDurationHours?.let { (it * 3600 * 1000).toLong() } ?: meta.first

            // Divide into logical blocks of 20-30 minutes (default 25 min = 1,500,000 ms)
            val targetBlockDurationMs = 25 * 60 * 1000L
            val totalBlocks = ((durationMs + targetBlockDurationMs - 1) / targetBlockDurationMs).toInt().coerceAtLeast(1)

            val projectId = repository.createProject(
                title = title.ifBlank { "Live_${System.currentTimeMillis() / 1000}" },
                videoUri = videoUri.toString(),
                videoDurationMs = durationMs,
                totalBlocks = totalBlocks,
                analysisMode = mode.name,
                whisperModel = whisperModel.name
            )

            selectProject(projectId)

            // Start foreground service for reliable background processing with shared processor instance
            VideoAnalysisService.sharedProcessor = processor
            VideoAnalysisService.startService(getApplication(), projectId)
        }
    }

    fun pauseAnalysis() {
        VideoAnalysisService.pauseService(getApplication())
        processor.pause()
        _selectedProjectId.value?.let { id ->
            viewModelScope.launch {
                repository.updateProjectStatus(id, ProjectStatus.PAUSED.name)
            }
        }
    }

    fun resumeAnalysis() {
        VideoAnalysisService.resumeService(getApplication())
        processor.resume()
        _selectedProjectId.value?.let { id ->
            viewModelScope.launch {
                repository.updateProjectStatus(id, ProjectStatus.ANALYZING.name)
                processor.processVideo(id)
            }
        }
    }

    fun cancelAnalysis() {
        VideoAnalysisService.cancelService(getApplication())
        processor.cancel()
        _selectedProjectId.value?.let { id ->
            viewModelScope.launch {
                repository.updateProjectStatus(id, ProjectStatus.IDLE.name)
            }
        }
    }

    fun toggleCandidateSelection(candidateId: Long, isSelected: Boolean) {
        viewModelScope.launch {
            repository.toggleCandidateSelection(candidateId, isSelected)
        }
    }

    fun setAllCandidatesSelected(isSelected: Boolean) {
        val projId = _selectedProjectId.value ?: return
        viewModelScope.launch {
            repository.setAllCandidatesSelected(projId, isSelected)
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            if (_selectedProjectId.value == projectId) {
                _selectedProjectId.value = null
                _currentProject.value = null
                _allCandidates.value = emptyList()
            }
            refreshTempSize()
        }
    }

    /**
     * Executes the heavy rendering/cutting ONLY on the user-selected candidate moments.
     * Never re-encodes the entire 10h video.
     */
    fun createCuts() {
        val proj = _currentProject.value ?: return
        val projId = proj.id
        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            _exportProgress.value = 0f
            val selected = repository.getSelectedCandidates(projId)
            if (selected.isEmpty()) {
                _isExporting.value = false
                return@launch
            }

            val results = mutableListOf<ClipExportResult>()
            val uri = Uri.parse(proj.videoUri)

            selected.forEachIndexed { index, candidate ->
                val clipRes = clipper.cutClip(
                    sourceUri = uri,
                    candidateId = candidate.id,
                    startMs = candidate.startMs,
                    endMs = candidate.endMs,
                    transcription = candidate.transcription,
                    keywords = candidate.keywords,
                    settings = _exportSettings.value
                ) { p ->
                    val overallProgress = (index + p) / selected.size.toFloat()
                    _exportProgress.value = overallProgress
                }
                results.add(clipRes)
            }

            _exportedResults.value = results
            _isExporting.value = false
            _exportProgress.value = 1f

            if (_exportSettings.value.autoCleanTempFiles) {
                cleanTempFiles()
            }
        }
    }
}
