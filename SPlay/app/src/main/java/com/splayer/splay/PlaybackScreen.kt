package com.splayer.splay

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
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
    var cover by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    val artworkRepository = remember(context) { SmbArtworkRepository(context) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(item.path, item.kind) {
        if (item.kind == MediaKind.MUSIC) cover = artworkRepository.get(settings, item.path)
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = player.currentPosition
            delay(400)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { playFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover!!.asImageBitmap(),
                        contentDescription = "Обложка альбома",
                        modifier = Modifier.size(300.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(300.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF252B34))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("♫", fontSize = 96.sp, color = Color(0xFF9EBCE0))
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(item.name, color = Color.White, fontSize = 23.sp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xE6101216)).padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(item.name, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(formatTime(position), color = Color(0xFFADB5C2), fontSize = 13.sp)
                Button(onClick = onPrevious) { Text("Предыдущий") }
                Button(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                    Text("−10 сек")
                }
                Button(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.focusRequester(playFocus)
                ) { Text(if (playing) "Пауза" else "Продолжить") }
                Button(onClick = { player.seekTo(player.currentPosition + 10_000L) }) {
                    Text("+10 сек")
                }
                Button(onClick = onNext) { Text("Следующий") }
                Text(formatTime(player.duration), color = Color(0xFFADB5C2), fontSize = 13.sp)
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
