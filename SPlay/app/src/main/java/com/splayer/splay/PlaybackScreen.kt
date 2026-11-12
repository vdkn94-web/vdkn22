package com.splayer.splay

import android.content.Context
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

@Composable
fun PlaybackScreen(
    context: Context,
    item: MediaItem,
    player: ExoPlayer,
    settings: SmbSettings,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    var trackInfo by remember(item.path) {
        mutableStateOf(TrackInfo(item.name.substringBeforeLast('.'), "Неизвестный исполнитель", null))
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var heldDirection by remember { mutableIntStateOf(0) }
    var seekStarted by remember(item.path) { mutableStateOf(false) }
    var showVideoProgress by remember(item.path) { mutableStateOf(false) }
    var videoScaleFilled by remember(item.path) { mutableStateOf(false) }

    val artworkRepository = remember(context) { SmbArtworkRepository(context) }

    LaunchedEffect(heldDirection) {
        if (heldDirection != 0) {
            delay(450)
            seekStarted = true
            if (item.kind == MediaKind.VIDEO) showVideoProgress = true
            while (heldDirection != 0) {
                val target = if (heldDirection > 0) player.currentPosition + 10_000L else player.currentPosition - 10_000L
                player.seekTo(target.coerceAtLeast(0L).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it })
                delay(450)
            }
        } else if (seekStarted) {
            delay(1800)
            showVideoProgress = false
            seekStarted = false
        }
    }
    LaunchedEffect(item.path, item.kind) {
        if (item.kind == MediaKind.MUSIC) trackInfo = artworkRepository.getTrackInfo(settings, item.path)
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.kind == MediaKind.VIDEO) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = if (videoScaleFilled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        var centerDownAt = 0L
                        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                            val right = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            val left = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            val center = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            if (center) {
                                when (event.action) {
                                    android.view.KeyEvent.ACTION_DOWN -> {
                                        if (centerDownAt == 0L) centerDownAt = event.downTime
                                        return true
                                    }
                                    android.view.KeyEvent.ACTION_UP -> {
                                        val heldMs = (event.eventTime - centerDownAt).coerceAtLeast(0L)
                                        centerDownAt = 0L
                                        if (heldMs >= 500L) {
                                            videoScaleFilled = !videoScaleFilled
                                            resizeMode = if (videoScaleFilled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        } else {
                                            if (player.isPlaying) player.pause() else player.play()
                                        }
                                        return true
                                    }
                                }
                            }
                            if (right || left) {
                                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (event.repeatCount == 0) heldDirection = if (right) 1 else -1
                                    return true
                                }
                                if (event.action == android.view.KeyEvent.ACTION_UP) {
                                    heldDirection = 0
                                    if (!seekStarted) {
                                        videoScaleFilled = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        if (right) onNext() else onPrevious()
                                    }
                                    return true
                                }
                            }
                            return super.dispatchKeyEvent(event)
                        }
                        requestFocus()
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = if (videoScaleFilled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { viewContext ->
                    View(viewContext).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        var centerDownAt = 0L
                        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                            val center = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            val right = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            val left = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            if (center) {
                                when (event.action) {
                                    android.view.KeyEvent.ACTION_DOWN -> {
                                        if (centerDownAt == 0L) centerDownAt = event.downTime
                                        return true
                                    }
                                    android.view.KeyEvent.ACTION_UP -> {
                                        val heldMs = (event.eventTime - centerDownAt).coerceAtLeast(0L)
                                        centerDownAt = 0L
                                        if (heldMs < 500L) { if (player.isPlaying) player.pause() else player.play() }
                                        return true
                                    }
                                }
                            }
                            if (right || left) {
                                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (event.repeatCount == 0) heldDirection = if (right) 1 else -1
                                    return true
                                }
                                if (event.action == android.view.KeyEvent.ACTION_UP) {
                                    heldDirection = 0
                                    if (!seekStarted) { if (right) onNext() else onPrevious() }
                                    return true
                                }
                            }
                            return super.dispatchKeyEvent(event)
                        }
                        requestFocus()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Column(
                Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val cover = trackInfo.cover
                if (cover != null) {
                    Image(bitmap = cover.asImageBitmap(), contentDescription = "Обложка альбома", modifier = Modifier.size(360.dp))
                } else {
                    Surface(
                        modifier = Modifier.size(360.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF252B34))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("♫", fontSize = 110.sp, color = Color(0xFF9EBCE0))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(trackInfo.title, color = Color.White, fontSize = 25.sp)
                Spacer(Modifier.height(6.dp))
                Text(trackInfo.artist, color = Color(0xFFADB5C2), fontSize = 18.sp)
            }
        }

        if (item.kind == MediaKind.MUSIC || showVideoProgress) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x99000000)).padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Color.White, fontSize = 13.sp)
                    Text(formatTime(duration), color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.height(7.dp))
                val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                    val y = size.height / 2f
                    drawLine(Color(0xFF737B86), Offset(0f, y), Offset(size.width, y), size.height, cap = StrokeCap.Round)
                    if (fraction > 0f) drawLine(Color.White, Offset(0f, y), Offset(size.width * fraction, y), size.height, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds < 0) return "--:--"
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
