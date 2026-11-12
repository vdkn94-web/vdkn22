package com.splayer.splay

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
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
    onNext: () -> Unit
) {
    var trackInfo by remember(item.path) {
        mutableStateOf(TrackInfo(item.name.substringBeforeLast('.'), "Неизвестный исполнитель", null))
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var controlsVisible by remember(item.path) { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var longSeekLeft by remember { mutableStateOf(false) }
    var longSeekRight by remember { mutableStateOf(false) }
    val artworkRepository = remember(context) { SmbArtworkRepository(context) }
    val playFocus = remember { FocusRequester() }

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
    LaunchedEffect(controlsVisible, interaction) {
        if (controlsVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.action == AndroidKeyEvent.ACTION_DOWN) {
                    controlsVisible = true
                    interaction++
                    when (key.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (key.repeatCount > 0) {
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(if (player.duration > 0) player.duration else Long.MAX_VALUE))
                                longSeekRight = true
                            }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (key.repeatCount > 0) {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                longSeekLeft = true
                            }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> true
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                } else if (key.action == AndroidKeyEvent.ACTION_UP) {
                    when (key.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!longSeekRight) player.seekTo((player.currentPosition + 10_000L).coerceAtMost(if (player.duration > 0) player.duration else Long.MAX_VALUE))
                            longSeekRight = false
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!longSeekLeft) player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                            longSeekLeft = false
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                            controlsVisible = !controlsVisible
                            interaction++
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                } else false
            }
    ) {
        if (item.kind == MediaKind.VIDEO) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        this.player = player
                        keepScreenOn = true
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val cover = trackInfo.cover
                if (cover != null) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = "Обложка альбома",
                        modifier = Modifier.size(360.dp)
                    )
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
                    .background(Color(0xE6101216)).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (item.kind == MediaKind.VIDEO) {
                    Text(item.name, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatTime(position), color = Color(0xFFADB5C2), fontSize = 13.sp)
                    Button(onClick = {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }) { Text("⇄") }
                    Button(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) {
                        Text(if (player.shuffleModeEnabled) "⤨" else "⇄")
                    }
                    Button(onClick = onPrevious) { Text("◀|") }
                    Button(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.focusRequester(playFocus)
                    ) { Text(if (playing) "Ⅱ" else "▶") }
                    Button(onClick = onNext) { Text("|▶") }
                    Button(onClick = {
                        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                    }) { Text(if (player.repeatMode == Player.REPEAT_MODE_ONE) "↻¹" else "↻") }
                    Text(formatTime(duration), color = Color(0xFFADB5C2), fontSize = 13.sp)
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
