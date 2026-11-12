package com.splayer.splay

import android.content.Context
import android.view.View
import androidx.compose.foundation.Image
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
    var controlsVisible by remember(item.path) { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var heldDirection by remember { mutableIntStateOf(0) }
    var seekStarted by remember { mutableStateOf(false) }
    var videoScaleFilled by remember(item.path) { mutableStateOf(false) }

    val artworkRepository = remember(context) { SmbArtworkRepository(context) }
    val playFocus = remember { FocusRequester() }
    val focusModifier = Modifier.onFocusChanged { controlsFocused = it.isFocused }

    LaunchedEffect(heldDirection) {
        if (heldDirection != 0) {
            delay(450)
            seekStarted = true
            while (heldDirection != 0) {
                val target = if (heldDirection > 0) player.currentPosition + 10_000L else player.currentPosition - 10_000L
                player.seekTo(target.coerceAtLeast(0L).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it })
                delay(450)
            }
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
    LaunchedEffect(controlsVisible, interaction, controlsFocused) {
        if (controlsVisible && !controlsFocused) {
            delay(5_000)
            if (!controlsFocused) controlsVisible = false
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
                        setOnKeyListener { _, keyCode, event ->
                            val right = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            val left = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            val down = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            val center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            when (event.action) {
                                android.view.KeyEvent.ACTION_DOWN -> when {
                                    down -> {
                                        controlsVisible = true
                                        interaction++
                                        runCatching { playFocus.requestFocus() }
                                        true
                                    }
                                    center -> {
                                        controlsVisible = true
                                        interaction++
                                        runCatching { playFocus.requestFocus() }
                                        true
                                    }
                                    right -> { controlsVisible = true; heldDirection = 1; true }
                                    left -> { controlsVisible = true; heldDirection = -1; true }
                                    else -> false
                                }
                                android.view.KeyEvent.ACTION_UP -> when {
                                    right -> {
                                        heldDirection = 0
                                        if (!seekStarted) onNext()
                                        seekStarted = false
                                        true
                                    }
                                    left -> {
                                        heldDirection = 0
                                        if (!seekStarted) onPrevious()
                                        seekStarted = false
                                        true
                                    }
                                    else -> false
                                }
                                else -> false
                            }
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
                        setOnKeyListener { _, keyCode, event ->
                            val down = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            val center = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            val right = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            val left = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            when (event.action) {
                                android.view.KeyEvent.ACTION_DOWN -> when {
                                    down || center -> {
                                        controlsVisible = true
                                        interaction++
                                        runCatching { playFocus.requestFocus() }
                                        true
                                    }
                                    right -> { controlsVisible = true; heldDirection = 1; true }
                                    left -> { controlsVisible = true; heldDirection = -1; true }
                                    else -> false
                                }
                                android.view.KeyEvent.ACTION_UP -> when {
                                    right -> {
                                        heldDirection = 0
                                        if (!seekStarted) onNext()
                                        seekStarted = false
                                        true
                                    }
                                    left -> {
                                        heldDirection = 0
                                        if (!seekStarted) onPrevious()
                                        seekStarted = false
                                        true
                                    }
                                    else -> false
                                }
                                else -> false
                            }
                        }
                        requestFocus()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Column(
                Modifier.fillMaxSize().padding(24.dp),
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

        if (controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xE6101216)).padding(horizontal = 28.dp, vertical = 12.dp)
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (item.kind == MediaKind.VIDEO) {
                    Text(item.name, color = Color.White, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerIconControl("⤨", "Перемешивание", player.shuffleModeEnabled, playFocus, { controlsFocused = it }) {
                        player.shuffleModeEnabled = !player.shuffleModeEnabled
                    }
                    PlayerIconControl("|◀", "Предыдущий трек или видео", onFocused = { controlsFocused = it }, onClick = onPrevious)
                    PlayerIconControl(if (playing) "Ⅱ" else "▶", "Пауза или воспроизведение", onFocused = { controlsFocused = it }) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    PlayerIconControl("▶|", "Следующий трек или видео", onFocused = { controlsFocused = it }, onClick = onNext)
                    PlayerIconControl(
                        if (player.repeatMode == Player.REPEAT_MODE_ONE) "↻¹" else "↻",
                        "Повтор",
                        player.repeatMode != Player.REPEAT_MODE_OFF,
                        onFocused = { controlsFocused = it }
                    ) {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                    if (item.kind == MediaKind.VIDEO) {
                        PlayerIconControl(
                            if (videoScaleFilled) "⛶" else "□",
                            "Масштаб: вписать или заполнить",
                            videoScaleFilled,
                            onFocused = { controlsFocused = it }
                        ) { videoScaleFilled = !videoScaleFilled }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Color(0xFFADB5C2), fontSize = 12.sp)
                    Text(formatTime(duration), color = Color(0xFFADB5C2), fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
private fun PlayerIconControl(
    symbol: String,
    description: String,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val controlModifier = Modifier
        .size(64.dp)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            onFocused(it.isFocused)
        }
        .clickable(onClick = onClick)
        .focusable()
    Box(modifier = controlModifier, contentAlignment = Alignment.Center) {
        Text(
            text = symbol,
            color = if (selected || focused) Color.White else Color(0xFFE8E8E8),
            fontSize = 31.sp,
            maxLines = 1
        )
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
