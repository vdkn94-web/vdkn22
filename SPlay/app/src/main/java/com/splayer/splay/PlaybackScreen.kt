package com.splayer.splay

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    upcomingItems: List<MediaItem> = emptyList(),
    queueItems: List<MediaItem> = emptyList(),
    onSelectQueueItem: (MediaItem) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onStop: () -> Unit
) {
    var trackInfo by remember(item.path) {
        mutableStateOf(TrackInfo(item.name.substringBeforeLast('.'), "Неизвестный исполнитель", null))
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var paused by remember(player) { mutableStateOf(!player.playWhenReady) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var heldDirection by remember { mutableIntStateOf(0) }
    var seekStarted by remember(item.path) { mutableStateOf(false) }
    var showVideoOverlay by remember(item.path) { mutableStateOf(false) }
    var videoOverlayEvent by remember(item.path) { mutableIntStateOf(0) }
    var videoScaleFilled by remember(item.path) { mutableStateOf(false) }
    var queueVisible by remember { mutableStateOf(false) }
    var selectedQueueIndex by remember(item.path, queueItems) {
        mutableIntStateOf(queueItems.indexOfFirst { it.path == item.path }.coerceAtLeast(0))
    }
    val queueListState = rememberLazyListState()
    LaunchedEffect(queueVisible, selectedQueueIndex, queueItems.size) {
        if (queueVisible && queueItems.isNotEmpty()) {
            queueListState.animateScrollToItem(selectedQueueIndex.coerceIn(0, queueItems.lastIndex))
        }
    }

    val artworkRepository = remember(context) { SmbArtworkRepository(context) }
    val playbackPlayer = player
    val playbackFocus = remember { FocusRequester() }
    var centerDownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { runCatching { playbackFocus.requestFocus() } }

    LaunchedEffect(heldDirection) {
        if (heldDirection != 0) {
            delay(450)
            seekStarted = true
            if (item.kind == MediaKind.VIDEO) {
                showVideoOverlay = true
                videoOverlayEvent++
            }
            while (heldDirection != 0) {
                val target = if (heldDirection > 0) player.currentPosition + 10_000L else player.currentPosition - 10_000L
                player.seekTo(target.coerceAtLeast(0L).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it })
                delay(450)
            }
        } else if (seekStarted) {
            delay(1800)
            seekStarted = false
        }
    }
    LaunchedEffect(item.path) {
        if (item.kind == MediaKind.VIDEO) {
            showVideoOverlay = true
            videoOverlayEvent++
        }
    }
    LaunchedEffect(videoOverlayEvent, heldDirection, item.path) {
        if (showVideoOverlay && heldDirection == 0 && item.kind == MediaKind.VIDEO) {
            delay(3000)
            showVideoOverlay = false
        }
    }
    LaunchedEffect(item.path, item.kind) {
        SPlayLog.info("PlaybackUI", "Screen bound path=${item.path} kind=${item.kind}")
        if (item.kind == MediaKind.MUSIC) {
            val started = android.os.SystemClock.elapsedRealtime()
            trackInfo = artworkRepository.getTrackInfo(settings, item.path)
            SPlayLog.info("PlaybackUI", "Current metadata ready path=${item.path} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        }
    }
    LaunchedEffect(item.path, upcomingItems) {
        if (item.kind == MediaKind.MUSIC) {
            val next = upcomingItems.filter { it.kind == MediaKind.MUSIC && it.path != item.path }.take(2)
            SPlayLog.info("Prefetch", "Queue current=${item.path}; next=${next.joinToString { it.path }}")
            artworkRepository.prefetch(settings, next.map { it.path })
        }
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            paused = !player.playWhenReady && player.playbackState != Player.STATE_IDLE
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { event ->
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                val right = event.key == Key.DirectionRight
                val left = event.key == Key.DirectionLeft
                val up = event.key == Key.DirectionUp
                val down = event.key == Key.DirectionDown
                val back = event.key == Key.Back || event.key == Key.Escape
                when {
                    back && event.type == KeyEventType.KeyUp && queueVisible -> {
                        queueVisible = false
                        true
                    }
                    down && event.type == KeyEventType.KeyUp -> {
                        if (queueVisible) {
                            selectedQueueIndex = (selectedQueueIndex + 1).coerceAtMost(queueItems.lastIndex.coerceAtLeast(0))
                        } else if (queueItems.isNotEmpty()) {
                            selectedQueueIndex = queueItems.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                            queueVisible = true
                        }
                        true
                    }
                    up && event.type == KeyEventType.KeyUp && queueVisible -> {
                        selectedQueueIndex = (selectedQueueIndex - 1).coerceAtLeast(0)
                        true
                    }
                    up && event.type == KeyEventType.KeyUp -> {
                        onToggleFavorite()
                        true
                    }
                    center && event.type == KeyEventType.KeyDown && queueVisible -> true
                    center && event.type == KeyEventType.KeyUp && queueVisible -> {
                        queueItems.getOrNull(selectedQueueIndex)?.let(onSelectQueueItem)
                        queueVisible = false
                        true
                    }
                    center && event.type == KeyEventType.KeyDown -> {
                        if (centerDownAt == 0L) centerDownAt = android.os.SystemClock.uptimeMillis()
                        true
                    }
                    center && event.type == KeyEventType.KeyUp -> {
                        val heldMs = (android.os.SystemClock.uptimeMillis() - centerDownAt).coerceAtLeast(0L)
                        centerDownAt = 0L
                        if (item.kind == MediaKind.VIDEO) {
                            showVideoOverlay = true
                            videoOverlayEvent++
                        }
                        if (item.kind == MediaKind.VIDEO && heldMs >= 500L) {
                            videoScaleFilled = !videoScaleFilled
                            SPlayLog.info("VideoScale", "Long OK path=${item.path} heldMs=$heldMs target=${if (videoScaleFilled) "ZOOM" else "FIT"}")
                        } else if (heldMs < 500L) {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                        true
                    }
                    (right || left) && queueVisible -> true
                    (right || left) && event.type == KeyEventType.KeyDown -> {
                        heldDirection = if (right) 1 else -1
                        true
                    }
                    (right || left) && event.type == KeyEventType.KeyUp -> {
                        heldDirection = 0
                        if (!seekStarted) {
                            videoScaleFilled = false
                            if (right) onNext() else onPrevious()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(playbackFocus)
            .focusable()
    ) {
        if (item.kind == MediaKind.VIDEO) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.splay_player_view, null, false) as PlayerView).apply {
                        this.player = playbackPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                update = {
                    it.player = playbackPlayer
                    it.resizeMode = if (videoScaleFilled) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    it.requestLayout()
                    SPlayLog.debug("VideoScale", "Applied path=${item.path} mode=${if (videoScaleFilled) "ZOOM" else "FIT"} size=${playbackPlayer.videoSize.width}x${playbackPlayer.videoSize.height} ratio=${playbackPlayer.videoSize.pixelWidthHeightRatio}")
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 112.dp)
            ) {
                val coverSize = minOf(440.dp, maxHeight * 0.82f)
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(42.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cover = trackInfo.cover
                    if (cover != null) {
                        Image(bitmap = cover.asImageBitmap(), contentDescription = "Обложка альбома", modifier = Modifier.size(coverSize))
                    } else {
                        Surface(
                            modifier = Modifier.size(coverSize),
                            shape = RoundedCornerShape(18.dp),
                            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF252B34))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("♫", fontSize = 140.sp, color = Color(0xFF9EBCE0))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(trackInfo.artist, color = Color(0xFFADB5C2), fontSize = 22.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee())
                        Spacer(Modifier.height(14.dp))
                        Text(trackInfo.title, color = Color.White, fontSize = 30.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee())
                    }
                }
            }
        }

        if (queueVisible) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(28.dp).width(420.dp)
                    .background(Color(0xF21A1F27), RoundedCornerShape(18.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Очередь воспроизведения", color = Color.White, fontSize = 21.sp)
                Text("Вниз / Вверх — выбрать · OK — включить · Назад — закрыть", color = Color(0xFFADB5C2), fontSize = 12.sp)
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(queueItems, key = { it.path }) { queueItem ->
                        val index = queueItems.indexOf(queueItem)
                        Row(
                            Modifier.fillMaxWidth().background(
                                if (index == selectedQueueIndex) Color(0xFF315B87) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (queueItem.path == item.path) "▶" else "${index + 1}.",
                                color = if (queueItem.path == item.path) Color(0xFFFF6B86) else Color(0xFFADB5C2),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                queueItem.name.let { if (queueItem.kind == MediaKind.VIDEO) it.substringBeforeLast('.', it) else it },
                                color = Color.White,
                                fontSize = 15.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        if (item.kind == MediaKind.MUSIC || (item.kind == MediaKind.VIDEO && showVideoOverlay)) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xCC080A0D)).padding(
                        horizontal = if (item.kind == MediaKind.VIDEO) 36.dp else 28.dp,
                        vertical = if (item.kind == MediaKind.VIDEO) 22.dp else 14.dp
                    )
            ) {
                if (item.kind == MediaKind.VIDEO) {
                    Text(
                        item.name.substringBeforeLast('.', item.name),
                        color = Color.White,
                        fontSize = 28.sp,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee()
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Color.White, fontSize = if (item.kind == MediaKind.VIDEO) 16.sp else 13.sp)
                    Text(formatTime(duration), color = Color.White, fontSize = if (item.kind == MediaKind.VIDEO) 16.sp else 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.weight(1f).height(if (item.kind == MediaKind.VIDEO) 6.dp else 5.dp)) {
                        val y = size.height / 2f
                        drawLine(Color(0xFF737B86), Offset(0f, y), Offset(size.width, y), size.height, cap = StrokeCap.Round)
                        if (fraction > 0f) drawLine(Color.White, Offset(0f, y), Offset(size.width * fraction, y), size.height, cap = StrokeCap.Round)
                    }
                    FavoriteHeart(
                        isFavorite = isFavorite,
                        onClick = {},
                        interactive = false,
                        modifier = Modifier.padding(start = 16.dp).size(30.dp)
                    )
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


@Composable
fun FavoriteHeart(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    val heartModifier = if (interactive) modifier.clickable(onClick = onClick).focusable() else modifier
    Canvas(heartModifier) {
        val w = size.width
        val h = size.height
        val heart = Path().apply {
            moveTo(w * 0.5f, h * 0.9f)
            cubicTo(w * 0.42f, h * 0.82f, w * 0.08f, h * 0.55f, w * 0.08f, h * 0.32f)
            cubicTo(w * 0.08f, h * 0.08f, w * 0.36f, h * 0.02f, w * 0.5f, h * 0.25f)
            cubicTo(w * 0.64f, h * 0.02f, w * 0.92f, h * 0.08f, w * 0.92f, h * 0.32f)
            cubicTo(w * 0.92f, h * 0.55f, w * 0.58f, h * 0.82f, w * 0.5f, h * 0.9f)
            close()
        }
        if (isFavorite) {
            drawPath(heart, Color(0xFFFF3158))
        } else {
            drawPath(heart, Color(0xFFE5EAF0), style = androidx.compose.ui.graphics.drawscope.Stroke(width = (w * 0.085f).coerceAtLeast(1.8f)))
        }
    }
}
