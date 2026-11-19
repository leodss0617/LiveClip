package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.drawable.Icon as ActionIcon
import android.util.Rational
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.ui.screens.MaquinaScreen
import com.example.ui.screens.ConfiguracoesScreen
import com.example.ui.screens.TermuxScreen
import com.example.ui.screens.CodespacesScreen
import com.example.ui.screens.rememberCodespacesSession
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Menu
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.example.service.LivePlayback
import com.example.ui.CortaJaViewModel
import com.example.ui.screens.LiveVideoSurface
import com.example.ui.screens.LivesScreen
import com.example.ui.screens.BibliotecaClipesScreen
import androidx.compose.material.icons.filled.LiveTv
import com.example.ui.screens.AnalysisProgressScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ResultsScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppNavScreen {
    HOME,
    PROGRESS,
    RESULTS,
    EXPORT,
    MAQUINA,
    TERMUX,
    CODESPACES,
    CONFIGURACOES,
    LIVES,
    BIBLIOTECA
}

class MainActivity : ComponentActivity() {

    private val viewModel: CortaJaViewModel by viewModels()
    // Compose observes videos received through Android's Share sheet, including while the app is already open.
    private var sharedVideoUri by mutableStateOf<Uri?>(null)
    private var sharedLink by mutableStateOf<String?>(null)
    private var liveRequests by mutableStateOf(0)
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                // Request Notification Permission on Android 13+
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {}

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val playback by LivePlayback.state.collectAsState()
                val showingLive = playback.player != null
                LaunchedEffect(showingLive) { updatePictureInPicture(showingLive) }
                LaunchedEffect(inPictureInPicture,playback.channel) {
                    if (inPictureInPicture && playback.channel == null) finish()
                }
                if (inPictureInPicture) {
                    val player = playback.player
                    if (player != null) LiveVideoSurface(player,Modifier.fillMaxSize(),controls=false)
                    else Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center) {
                        Text("Reconectando…",color=Color.White)
                    }
                } else {
                    CortaJaApp(viewModel = viewModel, initialSharedUri = sharedVideoUri, sharedLink = sharedLink,
                        onLinkRead = { sharedLink = null }, liveRequests = liveRequests)
                }
            }
        }
    }

    private fun supportsPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private fun pictureInPictureParams(enabled: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16,9))
        val stop = RemoteAction(ActionIcon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),
            "Parar live","Parar a reprodução ao vivo",LivePlayback.stopIntent(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setCloseAction(stop)
        else builder.setActions(listOf(stop))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(enabled)
        return builder.build()
    }

    private fun updatePictureInPicture(enabled: Boolean) {
        if (supportsPictureInPicture()) runCatching {
            setPictureInPictureParams(pictureInPictureParams(enabled))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (supportsPictureInPicture() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            LivePlayback.state.value.player != null) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams(true)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onStop() {
        if (inPictureInPicture && isFinishing) LivePlayback.stop(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            LivePlayback.SHOW -> liveRequests += 1
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                } else if (intent.type?.startsWith("video/") == true) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
                    }
                    sharedVideoUri = uri
                }
            }
            Intent.ACTION_VIEW -> {
                sharedVideoUri = intent.data
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CortaJaApp(
    viewModel: CortaJaViewModel,
    initialSharedUri: Uri? = null,
    sharedLink: String? = null,
    onLinkRead: () -> Unit = {},
    liveRequests: Int = 0
) {
    val codespaces = rememberCodespacesSession()
    val codespacesContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(codespaces.name, codespaces.autoConnect) {
        if (codespaces.autoConnect) codespaces.connect(codespacesContext)
    }
    val projects by viewModel.projects.collectAsState()
    val activeProject by viewModel.currentProject.collectAsState()
    val candidates by viewModel.allCandidates.collectAsState()
    val topLimit by viewModel.topLimit.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val progress by viewModel.analysisProgress.collectAsState()
    val tempSizeMb by viewModel.tempDirSizeMb.collectAsState()
    val exportSettings by viewModel.exportSettings.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportedClips by viewModel.exportedResults.collectAsState()

    var currentScreen by rememberSaveable { mutableStateOf(if (initialSharedUri != null) AppNavScreen.HOME else AppNavScreen.LIVES) }
    var recordingLink by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudLive by rememberSaveable { mutableStateOf<String?>(null) }
    var incomingLive by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedLive by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    DisposableEffect(expandedLive) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (expandedLive != null) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    var termuxJob by rememberSaveable { mutableStateOf<String?>(null) }
    val livesState = rememberSaveableStateHolder()
    val machineState = rememberSaveableStateHolder()
    val submission = remember { mutableStateOf(false) }
    LaunchedEffect(liveRequests) { if (liveRequests > 0) currentScreen = AppNavScreen.LIVES }
    LaunchedEffect(sharedLink) {
        if (!sharedLink.isNullOrEmpty()) {
            val channel = LivePlayback.channel(sharedLink)
            if (channel != null) {
                incomingLive = channel
                expandedLive = null
                currentScreen = AppNavScreen.LIVES
            } else {
                recordingLink = sharedLink
                expandedLive = null
                currentScreen = AppNavScreen.MAQUINA
            }
            onLinkRead()
        }
    }

    // If a project is actively analyzing, switch to progress screen
    LaunchedEffect(progress.isRunning, activeProject?.status) {
        if (progress.isRunning && currentScreen == AppNavScreen.HOME) {
            currentScreen = AppNavScreen.PROGRESS
        }
    }

    val destinations = listOf(
        Triple(AppNavScreen.LIVES, "Lives", Icons.Filled.LiveTv),
        Triple(AppNavScreen.MAQUINA, "Máquina de cortes", Icons.Filled.VideoLibrary),
        Triple(AppNavScreen.BIBLIOTECA, "Meus clipes", Icons.Filled.Folder),
        Triple(AppNavScreen.TERMUX, "Termux", Icons.Filled.Terminal),
        Triple(AppNavScreen.CODESPACES, "Codespaces", Icons.Filled.Terminal),
        Triple(AppNavScreen.CONFIGURACOES, "Ajustes", Icons.Filled.Settings),
        Triple(AppNavScreen.HOME, "Editor de arquivos", Icons.Filled.VideoLibrary)
    )
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val navigationScope = rememberCoroutineScope()
    val destination = destinations.firstOrNull { it.first == currentScreen }
    BackHandler(destination != null && currentScreen != AppNavScreen.LIVES &&
        expandedLive == null && !drawer.isOpen) {
        currentScreen = AppNavScreen.LIVES
    }

    BackHandler(drawer.isOpen) { navigationScope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = expandedLive == null && (drawer.isOpen || destination != null),
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("EditorClipesDSS", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp))
                    destinations.forEach { (screen, title, icon) ->
                        NavigationDrawerItem(
                            label = { Text(title) },
                            selected = currentScreen == screen,
                            icon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                if (screen == AppNavScreen.TERMUX) termuxJob = null
                                if (screen == AppNavScreen.CODESPACES) cloudLive = null
                                currentScreen = screen
                                navigationScope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = if (expandedLive != null) WindowInsets(0, 0, 0, 0)
            else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (destination != null && expandedLive == null) {
                TopAppBar(
                    title = { Text(destination.second) },
                    navigationIcon = {
                        IconButton(onClick = { navigationScope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Abrir menu lateral")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Crossfade(
            targetState = currentScreen,
            modifier = Modifier.padding(innerPadding),
            label = "ScreenTransition"
        ) { screen ->
            when (screen) {
                AppNavScreen.BIBLIOTECA -> BibliotecaClipesScreen()
                AppNavScreen.CODESPACES -> CodespacesScreen(codespaces, cloudLive)
                AppNavScreen.TERMUX -> TermuxScreen(selectedJob = termuxJob)
                AppNavScreen.LIVES -> livesState.SaveableStateProvider("lives") {
                    LivesScreen(
                        incomingLink = incomingLive,
                        onLinkConsumed = { incomingLive = null },
                        expanded = expandedLive,
                        onExpand = { expandedLive = it },
                        preferCloud = codespaces.preferCloud,
                        onCloud = { channel ->
                            codespaces.connect(codespacesContext, channel)
                            cloudLive = channel
                            expandedLive = null
                            currentScreen = AppNavScreen.CODESPACES
                        },
                        onTermux = {
                            termuxJob = it
                            currentScreen = AppNavScreen.TERMUX
                        },
                    )
                }
                AppNavScreen.CONFIGURACOES -> ConfiguracoesScreen(
                    onOpenEditor = { currentScreen = AppNavScreen.HOME }
                )
                AppNavScreen.MAQUINA -> machineState.SaveableStateProvider("maquina") {
                    MaquinaScreen(
                        sharedLink = recordingLink,
                        onSharedLinkConsumed = { recordingLink = null },
                        submission = submission,
                        onOpenSettings = { currentScreen = AppNavScreen.CONFIGURACOES },
                        onOpenLives = { currentScreen = AppNavScreen.LIVES },
                    )
                }
                AppNavScreen.HOME -> {
                    HomeScreen(
                        projects = projects,
                        tempSizeMb = tempSizeMb,
                        onCleanTempFiles = { viewModel.cleanTempFiles() },
                        onStartProject = { uri, title, mode, whisperModel, forcedHours ->
                            viewModel.startNewAnalysis(uri, title, mode, whisperModel, forcedHours)
                            currentScreen = AppNavScreen.PROGRESS
                        },
                        onSelectProject = { id ->
                            viewModel.selectProject(id)
                            val proj = projects.find { it.id == id }
                            if (proj?.status == "COMPLETED") {
                                currentScreen = AppNavScreen.RESULTS
                            } else {
                                currentScreen = AppNavScreen.PROGRESS
                            }
                        },
                        onDeleteProject = { id ->
                            viewModel.deleteProject(id)
                        },
                        sharedVideoUri = initialSharedUri
                    )
                }

                AppNavScreen.PROGRESS -> {
                    val proj = activeProject ?: projects.firstOrNull()
                    if (proj != null) {
                        AnalysisProgressScreen(
                            project = proj,
                            progress = progress,
                            onPause = { viewModel.pauseAnalysis() },
                            onResume = { viewModel.resumeAnalysis() },
                            onCancel = {
                                viewModel.cancelAnalysis()
                                currentScreen = AppNavScreen.HOME
                            },
                            onViewResults = { currentScreen = AppNavScreen.RESULTS },
                            onBack = { currentScreen = AppNavScreen.HOME }
                        )
                    } else {
                        currentScreen = AppNavScreen.HOME
                    }
                }

                AppNavScreen.RESULTS -> {
                    ResultsScreen(
                        candidates = candidates,
                        topLimit = topLimit,
                        onSetTopLimit = { viewModel.setTopLimit(it) },
                        selectedCategory = selectedCategory,
                        onSetSelectedCategory = { viewModel.setSelectedCategory(it) },
                        onToggleCandidate = { id, isSelected ->
                            viewModel.toggleCandidateSelection(id, isSelected)
                        },
                        onSelectAll = { isSelected ->
                            viewModel.setAllCandidatesSelected(isSelected)
                        },
                        onCreateCuts = { currentScreen = AppNavScreen.EXPORT },
                        onBack = {
                            if (progress.isRunning) {
                                currentScreen = AppNavScreen.PROGRESS
                            } else {
                                currentScreen = AppNavScreen.HOME
                            }
                        },
                        videoUri = activeProject?.videoUri?.let { Uri.parse(it) } ?: Uri.EMPTY
                    )
                }

                AppNavScreen.EXPORT -> {
                    ExportScreen(
                        settings = exportSettings,
                        onUpdateSettings = { viewModel.updateExportSettings(it) },
                        isExporting = isExporting,
                        exportProgress = exportProgress,
                        exportedClips = exportedClips,
                        onStartRendering = { viewModel.createCuts() },
                        onBack = { currentScreen = AppNavScreen.RESULTS }
                    )
                }
            }
        }
    }
    }
}
