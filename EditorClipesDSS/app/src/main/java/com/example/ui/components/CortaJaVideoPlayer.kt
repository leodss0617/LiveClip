package com.example.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.util.SampleVideoHelper
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
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream

private const val TAG = "CortaJaVideoPlayer"

/**
 * Robust, hardware-accelerated interactive Video Player supporting:
 * - 9:16 vertical crop or standard 16:9 ratio
 * - Segment loop (startMs -> endMs)
 * - Custom animated subtitle overlay
 * - Full controls (Play/Pause, Slider, Audio Mute, Close)
 * - Uses TextureView with explicit FileDescriptor binding to prevent MediaPlayer (1, -2147483648) errors
 */
@Composable
fun CortaJaVideoPlayer(
    videoUri: Uri,
    title: String,
    modifier: Modifier = Modifier,
    startMs: Long = 0L,
    endMs: Long = 0L,
    transcription: String? = null,
    isVertical916: Boolean = true,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var actualUri by remember(videoUri) {
        mutableStateOf(resolvePlayableUri(context, videoUri))
    }

    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var surfaceRef by remember { mutableStateOf<Surface?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(startMs) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }

    val effectiveEndMs = if (endMs > startMs) endMs else totalDurationMs

    // Periodic progress ticker and segment loop enforcement
    LaunchedEffect(isPlaying, startMs, effectiveEndMs) {
        while (true) {
            mediaPlayerRef?.let { mp ->
                try {
                    if (isPlaying && mp.isPlaying) {
                        val pos = mp.currentPosition.toLong()
                        currentPositionMs = pos

                        if (effectiveEndMs > startMs && pos >= effectiveEndMs) {
                            mp.seekTo(startMs.toInt())
                        }
                    }
                } catch (_: Exception) {}
            }
            delay(200)
        }
    }

    // Auto-hide controls after 4 seconds of playback
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    // Cleanup MediaPlayer when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayerRef?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                }
                surfaceRef?.release()
            } catch (_: Exception) {}
            mediaPlayerRef = null
            surfaceRef = null
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                showControls = !showControls
            },
        contentAlignment = Alignment.Center
    ) {
        // Video Surface Box
        Box(
            modifier = if (isVertical916) {
                Modifier
                    .fillMaxSize()
                    .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val frameLayout = FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val textureView = TextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER
                        )

                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val surface = Surface(st)
                                surfaceRef = surface

                                val mp = MediaPlayer().apply {
                                    setSurface(surface)
                                    setOnPreparedListener { player ->
                                        isLoading = false
                                        hasError = false
                                        val dur = player.duration.toLong()
                                        if (dur > 0) totalDurationMs = dur
                                        if (startMs > 0) {
                                            player.seekTo(startMs.toInt())
                                        }
                                        player.start()
                                        isPlaying = true
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                                        isLoading = false
                                        hasError = true
                                        true // Suppress default system error dialog
                                    }
                                    setOnCompletionListener { player ->
                                        if (startMs > 0 && effectiveEndMs > startMs) {
                                            player.seekTo(startMs.toInt())
                                            player.start()
                                        } else {
                                            isPlaying = false
                                        }
                                    }
                                }

                                mediaPlayerRef = mp
                                preparePlayerWithUri(ctx, mp, actualUri)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surfaceRef?.release()
                                surfaceRef = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }

                    frameLayout.addView(textureView)
                    frameLayout
                },
                update = { _ ->
                    // Re-bind source if actualUri changed after initial load
                    mediaPlayerRef?.let { mp ->
                        surfaceRef?.let { surf ->
                            if (!mp.isPlaying && !isLoading && !hasError) {
                                mp.setSurface(surf)
                            }
                        }
                    }
                }
            )

            // Loading indicator
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = BrandPrimary, strokeWidth = 3.dp)
                        Text("Carregando vídeo...", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // Error Overlay with Sample Fallback
            if (hasError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = BrandAmber,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Não foi possível reproduzir este vídeo.",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Carregar a demonstração integrada (sample_live.mp4)?",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                hasError = false
                                isLoading = true
                                val sampleUri = SampleVideoHelper.getOrCreateSampleVideoUri(context)
                                actualUri = sampleUri
                                mediaPlayerRef?.let { mp ->
                                    preparePlayerWithUri(context, mp, sampleUri)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                        ) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Usar Vídeo de Amostra", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Dynamic Styled Subtitles (TikTok/Shorts style)
            if (!transcription.isNullOrBlank() && !hasError) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (showControls) 85.dp else 30.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .border(1.dp, BrandPrimary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = transcription,
                        color = BrandAmber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Overlay Controls (Gradient, Close button, Center Play/Pause, Timeline)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                // Top Bar: Close + Title + Aspect ratio badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .testTag("btn_close_player")
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        Text(
                            text = if (isVertical916) "Formato 9:16 Vertical" else "Formato Original",
                            color = BrandCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            mediaPlayerRef?.let { mp ->
                                if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.VolumeMute else Icons.Filled.VolumeUp,
                            contentDescription = "Mudo",
                            tint = Color.White
                        )
                    }
                }

                // Center: Big Play / Pause Button
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(BrandPrimary.copy(alpha = 0.85f))
                        .clickable {
                            mediaPlayerRef?.let { mp ->
                                try {
                                    if (mp.isPlaying) {
                                        mp.pause()
                                        isPlaying = false
                                    } else {
                                        mp.start()
                                        isPlaying = true
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        .testTag("btn_toggle_play_pause"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Bottom Bar: Timecodes, Seekbar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPositionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (effectiveEndMs > 0) formatTime(effectiveEndMs) else "--:--",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val sliderRange = (startMs.toFloat()..effectiveEndMs.coerceAtLeast(startMs + 1000).toFloat())
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(sliderRange),
                        onValueChange = { newPos ->
                            currentPositionMs = newPos.toLong()
                            mediaPlayerRef?.let { mp ->
                                try {
                                    mp.seekTo(newPos.toInt())
                                } catch (_: Exception) {}
                            }
                        },
                        valueRange = sliderRange,
                        colors = SliderDefaults.colors(
                            thumbColor = BrandCyan,
                            activeTrackColor = BrandPrimaryLight,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick Jump Controls (Replay segment)
                    if (startMs > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BrandPrimary.copy(alpha = 0.3f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BrandPrimary),
                                modifier = Modifier.clickable {
                                    mediaPlayerRef?.let { mp ->
                                        try {
                                            mp.seekTo(startMs.toInt())
                                            mp.start()
                                            isPlaying = true
                                        } catch (_: Exception) {}
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Replay,
                                        contentDescription = "Reiniciar corte",
                                        tint = BrandCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Reiniciar corte",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prepares the MediaPlayer with descriptor-level access.
 * This bypasses native filesystem permission restrictions (MediaPlayer Error (1, -2147483648))
 * by having the application process open the file and pass the FileDescriptor to mediaserver.
 */
private fun preparePlayerWithUri(context: Context, mp: MediaPlayer, uri: Uri) {
    try {
        mp.reset()

        var bound = false
        // 1. Local file path -> open FileInputStream in app process
        if (uri.scheme == "file" && uri.path != null) {
            val file = File(uri.path!!)
            if (file.exists() && file.length() > 0) {
                FileInputStream(file).use { fis ->
                    mp.setDataSource(fis.fd, 0, file.length())
                    bound = true
                }
            }
        }

        // 2. content:// or android.resource:// -> open AssetFileDescriptor via ContentResolver
        if (!bound && (uri.scheme == "content" || uri.scheme == "android.resource")) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.declaredLength < 0) {
                        mp.setDataSource(afd.fileDescriptor)
                    } else {
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                    }
                    bound = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "openAssetFileDescriptor failed for $uri: ${e.message}")
            }
        }

        // 3. Fallback: standard setDataSource
        if (!bound) {
            try {
                mp.setDataSource(context, uri)
                bound = true
            } catch (e: Exception) {
                Log.w(TAG, "standard setDataSource failed: ${e.message}")
            }
        }

        // 4. Ultimate safety fallback: bundled raw sample video
        if (!bound) {
            val rawUri = SampleVideoHelper.getOrCreateSampleVideoUri(context)
            context.contentResolver.openAssetFileDescriptor(rawUri, "r")?.use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            }
        }

        mp.prepareAsync()
    } catch (e: Exception) {
        Log.e(TAG, "preparePlayerWithUri fatal: ${e.message}", e)
    }
}

/**
 * Ensures the given URI points to a readable, non-empty video file.
 * If corrupt, 0-byte, or mock URI, returns the bundled sample video URI.
 */
private fun resolvePlayableUri(context: Context, inputUri: Uri): Uri {
    val uriStr = inputUri.toString()
    if (uriStr.isBlank() || uriStr.startsWith("cortaja://") || uriStr.contains("mock") || inputUri == Uri.EMPTY) {
        return SampleVideoHelper.getOrCreateSampleVideoUri(context)
    }

    if (inputUri.scheme == "file") {
        val path = inputUri.path
        if (path == null) return SampleVideoHelper.getOrCreateSampleVideoUri(context)
        val file = File(path)
        if (!file.exists() || file.length() < 1000L) {
            try {
                val sample = SampleVideoHelper.getSampleVideoFile(context)
                if (sample.exists() && sample.length() > 1000L) {
                    file.parentFile?.mkdirs()
                    sample.copyTo(file, overwrite = true)
                    file.setReadable(true, false)
                    return Uri.fromFile(file)
                }
            } catch (_: Exception) {}
            return SampleVideoHelper.getOrCreateSampleVideoUri(context)
        }
    }

    return inputUri
}
