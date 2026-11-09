package com.splayer.splay

import android.net.Uri
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private val AppBackground = Color(0xFF0B1018)
private val PanelColor = Color(0xB8202938)
private val AccentColor = Color(0xFF9BB7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 16, 24)
        window.navigationBarColor = android.graphics.Color.rgb(11, 16, 24)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentColor,
                    secondary = Color(0xFF80A9FF),
                    background = AppBackground,
                    surface = PanelColor
                )
            ) {
                SPlayApp()
            }
        }
    }
}

private data class RemoteEntry(
    val name: String,
    val path: String,
    val directory: Boolean
)

private class SmbBrowser {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    fun connect(host: String, shareName: String, username: String, password: String, domain: String) {
        disconnect()
        val newClient = SMBClient()
        try {
            val newConnection = newClient.connect(host.trim())
            val auth = AuthenticationContext(
                username.trim(),
                password.toCharArray(),
                domain.trim()
            )
            val newSession = newConnection.authenticate(auth)
            val newShare = newSession.connectShare(shareName.trim()) as DiskShare
            client = newClient
            connection = newConnection
            session = newSession
            share = newShare
        } catch (error: Exception) {
            try { newClient.close() } catch (_: Exception) { }
            throw error
        }
    }

    private var listingDirectory: com.hierynomus.smbj.share.Directory? = null
    private var listingIterator: Iterator<FileIdBothDirectoryInformation>? = null
    private var listingPath: String? = null

    fun listPage(path: String, reset: Boolean, pageSize: Int = 80): Pair<List<RemoteEntry>, Boolean> {
        val activeShare = share ?: error("Сначала подключись к SMB-папке")
        if (reset || listingPath != path || listingIterator == null) {
            closeListing()
            listingDirectory = activeShare.openDirectory(
                path,
                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
                null,
                EnumSet.allOf(SMB2ShareAccess::class.java),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            listingIterator = listingDirectory!!.iterator()
            listingPath = path
        }

        val result = ArrayList<RemoteEntry>(pageSize)
        val iterator = listingIterator ?: return Pair(emptyList(), false)
        while (result.size < pageSize && iterator.hasNext()) {
            val item = iterator.next()
            val name = item.fileName
            if (name == "." || name == "..") continue
            val isDirectory = EnumWithValue.EnumUtils.isSet(
                item.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            )
            val childPath = if (path.isBlank()) name else "$path\\$name"
            result.add(RemoteEntry(name, childPath, isDirectory))
        }
        val hasMore = iterator.hasNext()
        if (!hasMore) closeListing()
        return Pair(result, hasMore)
    }

    private fun closeListing() {
        try { listingDirectory?.close() } catch (_: Exception) { }
        listingDirectory = null
        listingIterator = null
        listingPath = null
    }

    fun copyToPipe(path: String, pipePath: String) {
        val activeShare = share ?: error("Нет SMB-подключения")
        val remote = activeShare.openFile(
            path,
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.allOf(SMB2ShareAccess::class.java),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        remote.use { file ->
            file.inputStream.use { input ->
                FileOutputStream(pipePath).use { output -> input.copyTo(output, 256 * 1024) }
            }
        }
    }

    fun disconnect() {
        closeListing()
        try { share?.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { connection?.close() } catch (_: Exception) { }
        try { client?.close() } catch (_: Exception) { }
        share = null
        session = null
        connection = null
        client = null
    }
}

@Composable
private fun SPlayApp() {
    val context = LocalContext.current
    val browser = remember { SmbBrowser() }
    val scope = rememberCoroutineScope()

    val preferences = remember { context.getSharedPreferences("splay_settings", android.content.Context.MODE_PRIVATE) }
    var host by rememberSaveable { mutableStateOf(preferences.getString("host", "") ?: "") }
    var shareName by rememberSaveable { mutableStateOf(preferences.getString("share", "") ?: "") }
    var videoFolder by rememberSaveable { mutableStateOf(preferences.getString("video_folder", "") ?: "") }
    var musicFolder by rememberSaveable { mutableStateOf(preferences.getString("music_folder", "") ?: "") }
    var username by rememberSaveable { mutableStateOf(preferences.getString("username", "") ?: "") }
    var password by rememberSaveable { mutableStateOf(preferences.getString("password", "") ?: "") }
    var domain by rememberSaveable { mutableStateOf(preferences.getString("domain", "") ?: "") }
    var mediaSection by rememberSaveable { mutableStateOf("Все") }
    var sidebarExpanded by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var hasMoreEntries by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf<RemoteEntry?>(null) }
    var videoMode by remember { mutableStateOf(false) }
    var playbackPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableStateOf(0L) }
    var playbackDuration by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var shuffleEnabled by rememberSaveable { mutableStateOf(false) }
    var playQueue by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val contentFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val sidebarFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var mediaArts by remember { mutableStateOf<Map<String, MediaArtResult>>(emptyMap()) }
    val artCache = remember { MediaArtCache(context) { path, pipe -> browser.copyToPipe(path, pipe) } }

    fun requestMediaArt(entry: RemoteEntry, audio: Boolean) {
        if (mediaArts.containsKey(entry.path)) return
        val accepted = artCache.request(entry.path, audio) { result ->
            scope.launch { mediaArts = mediaArts + (entry.path to result) }
        }
        if (!accepted) scope.launch {
            delay(800)
            requestMediaArt(entry, audio)
        }
    }

    val libVlc = remember { LibVLC(context, arrayListOf("--network-caching=1800")) }
    val mediaPlayer = remember { MediaPlayer(libVlc) }

    fun refresh(path: String) {
        busy = true
        errorText = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { browser.listPage(path, true) }
                entries = result.first
                hasMoreEntries = result.second
                currentPath = path
                preferences.edit().putString("last_path", path).apply()
            } catch (e: Exception) {
                errorText = e.message ?: "Не удалось прочитать папку"
            } finally {
                busy = false
            }
        }
    }

    fun loadMore() {
        if (busy || !hasMoreEntries) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { browser.listPage(currentPath, false) }
                entries = entries + result.first
                hasMoreEntries = result.second
            } catch (e: Exception) {
                errorText = e.message ?: "Не удалось загрузить следующие файлы"
            } finally {
                busy = false
            }
        }
    }

    fun connect() {
        if (host.isBlank() || shareName.isBlank()) {
            errorText = "Укажи адрес сервера и имя общей папки (share)"
            return
        }
        busy = true
        errorText = null
        scope.launch {
            try {
                val initialPath = ""
                preferences.edit().remove("start_folder").putString("host", host.trim()).putString("share", shareName.trim()).putString("last_path", initialPath).putString("username", username.trim()).putString("password", password).putString("domain", domain.trim()).apply()
                val initialEntries = withContext(Dispatchers.IO) {
                    browser.connect(host, shareName, username, password, domain)
                    browser.listPage(initialPath, true)
                }
                entries = initialEntries.first
                hasMoreEntries = initialEntries.second
                currentPath = initialPath
                preferences.edit().putString("last_path", initialPath).apply()
                connected = true
            } catch (e: Exception) {
                connected = false
                errorText = e.message ?: "Не удалось подключиться к SMB"
            } finally {
                busy = false
            }
        }
    }

    fun play(entry: RemoteEntry) {
        val relative = entry.path.replace("\\", "/")
        val encodedShare = Uri.encode(shareName.trim(), "/")
        val encodedPath = Uri.encode(relative, "/")
        val streamUrl = "smb://${host.trim()}/$encodedShare/$encodedPath"
        try {
            val media = Media(libVlc, Uri.parse(streamUrl))
            media.addOption(":network-caching=1800")
            if (username.isNotBlank()) media.addOption(":smb-user=$username")
            if (password.isNotBlank()) media.addOption(":smb-pwd=$password")
            if (domain.isNotBlank()) media.addOption(":smb-domain=$domain")
            mediaPlayer.media = media
            media.release()
            val candidates = entries.filter { !it.directory && (isVideo(it.name) || isAudio(it.name)) }.sortedBy { it.name.lowercase() }
            playQueue = if (shuffleEnabled) candidates.shuffled() else candidates
            queueIndex = playQueue.indexOfFirst { it.path == entry.path }
            if (queueIndex < 0) { playQueue = listOf(entry); queueIndex = 0 }
            nowPlaying = entry
            controlsVisible = true
            videoMode = isVideo(entry.name)
            mediaPlayer.play()
            playbackPlaying = true
        } catch (e: Exception) {
            errorText = "VLC не смог открыть файл: ${e.message ?: "ошибка воспроизведения"}"
        }
    }

    LaunchedEffect(nowPlaying) {
        while (nowPlaying != null) {
            playbackPosition = mediaPlayer.time.coerceAtLeast(0L)
            playbackDuration = mediaPlayer.length.coerceAtLeast(0L)
            playbackPlaying = mediaPlayer.isPlaying
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, nowPlaying) {
        if (controlsVisible && nowPlaying != null) {
            delay(5000)
            controlsVisible = false
        }
    }

    fun playQueueStep(delta: Int) {
        if (playQueue.isEmpty()) return
        val next = (queueIndex + delta).coerceIn(0, playQueue.lastIndex)
        if (next != queueIndex) {
            val activeQueue = playQueue
            val selected = activeQueue[next]
            queueIndex = next
            play(selected)
            playQueue = activeQueue
            queueIndex = next
        }
    }

    LaunchedEffect(Unit) {
        if (host.isNotBlank() && shareName.isNotBlank()) connect()
    }

    LaunchedEffect(sidebarExpanded) {
        if (sidebarExpanded) {
            delay(60)
            sidebarFocusRequester.requestFocus()
        } else if (connected && nowPlaying == null && !busy && entries.isNotEmpty()) {
            delay(100)
            contentFocusRequester.requestFocus()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { mediaPlayer.stop() } catch (_: Exception) { }
            try { mediaPlayer.detachViews() } catch (_: Exception) { }
            mediaPlayer.release()
            libVlc.release()
            scope.launch(Dispatchers.IO) { browser.disconnect() }
        }
    }

    BackHandler(enabled = nowPlaying != null || sidebarExpanded || showSettings || (connected && currentPath.isNotBlank())) {
        if (nowPlaying != null) {
            mediaPlayer.stop()
            try { mediaPlayer.detachViews() } catch (_: Exception) { }
            nowPlaying = null
            videoMode = false
            playbackPlaying = false
        } else if (sidebarExpanded) {
            sidebarExpanded = false
        } else if (showSettings) {
            showSettings = false
        } else {
            val parent = currentPath.substringBeforeLast('\\', "")
            refresh(parent)
        }
    }

    val activeArt = mediaArts[nowPlaying?.path]
    val activeArtBitmap = rememberArtworkBitmap(activeArt?.image)

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        if (nowPlaying != null) {
            Column(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (!controlsVisible) controlsVisible = true
                            else if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                            true
                        }
                        Key.DirectionLeft -> { controlsVisible = true; mediaPlayer.time = (mediaPlayer.time - 10000L).coerceAtLeast(0L); true }
                        Key.DirectionRight -> { controlsVisible = true; mediaPlayer.time = (mediaPlayer.time + 10000L).coerceAtMost(mediaPlayer.length.coerceAtLeast(0L)); true }
                        Key.DirectionUp, Key.DirectionDown -> { controlsVisible = true; true }
                        else -> { controlsVisible = true; false }
                    }
                }
            }) {
                if (controlsVisible) Row(Modifier.fillMaxWidth().background(PanelColor).padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SPlay", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(16.dp))
                    Text(activeArt?.title ?: nowPlaying!!.name, modifier = Modifier.weight(1f), color = Color.LightGray, fontSize = 16.sp, maxLines = 1)
                    TextButton(onClick = {
                        mediaPlayer.stop()
                        try { mediaPlayer.detachViews() } catch (_: Exception) { }
                        nowPlaying = null
                        videoMode = false
                        playbackPlaying = false
                    }) { Text("Закрыть", color = AccentColor) }
                }
                if (videoMode) {
                    AndroidView(factory = { viewContext ->
                        VLCVideoLayout(viewContext).also { layout ->
                            videoLayout = layout
                            mediaPlayer.attachViews(layout, null, false, false)
                        }
                    }, modifier = Modifier.fillMaxWidth().weight(1f))
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101010)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (activeArtBitmap != null) {
                                Image(activeArtBitmap, contentDescription = "Обложка альбома", modifier = Modifier.width(240.dp).height(240.dp), contentScale = ContentScale.Crop)
                            } else {
                                Text("♫", fontSize = 88.sp, color = AccentColor)
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(activeArt?.title ?: nowPlaying!!.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(activeArt?.artist ?: "Воспроизведение аудио", color = Color.LightGray, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
                if (controlsVisible) Column(Modifier.fillMaxWidth().background(PanelColor).padding(horizontal = 28.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPlaybackTime(playbackPosition), color = Color.White, fontSize = 12.sp)
                        Slider(value = if (playbackDuration > 0) playbackPosition.toFloat().coerceIn(0f, playbackDuration.toFloat()) else 0f,
                            onValueChange = { playbackPosition = it.toLong(); mediaPlayer.time = it.toLong() },
                            valueRange = 0f..playbackDuration.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text(formatPlaybackTime(playbackDuration), color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { mediaPlayer.time = (mediaPlayer.time - 10000L).coerceAtLeast(0L) }) { Icon(Icons.Filled.FastRewind, null); Text("10") }
                        TextButton(onClick = {
                            shuffleEnabled = !shuffleEnabled
                            val current = nowPlaying
                            val rest = playQueue.filter { it.path != current?.path }
                            playQueue = if (shuffleEnabled) listOfNotNull(current) + rest.shuffled() else (listOfNotNull(current) + rest.sortedBy { it.name.lowercase() })
                            queueIndex = if (current == null) -1 else 0
                        }) { Icon(Icons.Filled.Shuffle, "Перемешать", tint = if (shuffleEnabled) AccentColor else Color.LightGray) }
                        TextButton(onClick = { playQueueStep(-1) }, enabled = queueIndex > 0) { Icon(Icons.Filled.SkipPrevious, "Предыдущий") }
                        Button(onClick = { if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play() }) { Text(if (playbackPlaying) "Ⅱ  Пауза" else "▶  Играть") }
                        TextButton(onClick = { playQueueStep(1) }, enabled = queueIndex < playQueue.lastIndex) { Icon(Icons.Filled.SkipNext, "Следующий") }
                        TextButton(onClick = { mediaPlayer.time = (mediaPlayer.time + 10000L).coerceAtMost(mediaPlayer.length.coerceAtLeast(0L)) }) { Text("10"); Icon(Icons.Filled.FastForward, null) }
                        TextButton(onClick = { controlsVisible = false }) { Text("Скрыть") }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                if (connected) {
                    Column(
                        Modifier
                            .width(if (sidebarExpanded) 232.dp else 72.dp)
                            .fillMaxSize()
                            .background(Color(0xAA151D29))
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    sidebarExpanded = false
                                    scope.launch { delay(140); contentFocusRequester.requestFocus() }
                                    true
                                } else false
                            }
                            .padding(horizontal = if (sidebarExpanded) 14.dp else 8.dp, vertical = 22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 28.dp)) {
                            Text("S", color = AccentColor, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            if (sidebarExpanded) Text("Play", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        }
                        if (sidebarExpanded) Text("БИБЛИОТЕКА", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
                        SidebarItem(Icons.Filled.VideoLibrary, "Видео", mediaSection == "Видео", sidebarExpanded, modifier = Modifier.focusRequester(sidebarFocusRequester)) { mediaSection = "Видео"; showSettings = false; showSearch = false; sidebarExpanded = false; refresh(videoFolder.trim().replace("/", "\\").trim('\\')) }
                        SidebarItem(Icons.Filled.MusicNote, "Музыка", mediaSection == "Музыка", sidebarExpanded) { mediaSection = "Музыка"; showSettings = false; showSearch = false; sidebarExpanded = false; refresh(musicFolder.trim().replace("/", "\\").trim('\\')) }
                        SidebarItem(Icons.Filled.Search, "Поиск", showSearch, sidebarExpanded) { showSearch = true; showSettings = false; sidebarExpanded = false }
                        Spacer(Modifier.weight(1f))
                        SidebarItem(Icons.Filled.Settings, "Настройки", showSettings, sidebarExpanded) { showSettings = true; sidebarExpanded = false }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxSize().padding(horizontal = 30.dp, vertical = 22.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && connected && !sidebarExpanded) {
                                sidebarExpanded = true
                                true
                            } else false
                        }
                ) {
                if (!connected) {
                    Text("SPlay", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                }
                if (!connected) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        Text("Подключение к SMB", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Укажи сетевой ресурс, чтобы открыть свою медиатеку", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(host, { host = it }, label = { Text("Сервер / IP") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(shareName, { shareName = it }, label = { Text("Общая папка (share)") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(username, { username = it }, label = { Text("Пользователь") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                            OutlinedTextField(domain, { domain = it }, label = { Text("Домен") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { connect() }, enabled = !busy) { Text(if (busy) "Подключаемся…" else "Подключиться к серверу") }
                        Spacer(Modifier.height(8.dp))
                        Text("Поддерживаются SMB2 и SMB3", color = Color.Gray, fontSize = 12.sp)
                    }
                } else if (showSettings) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        Text("Настройки", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        Text("СЕТЕВОЕ ПОДКЛЮЧЕНИЕ", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Сервер / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = shareName, onValueChange = { shareName = it }, label = { Text("Общая папка (share)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = videoFolder, onValueChange = { videoFolder = it }, label = { Text("Папка видео (от корня SMB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = musicFolder, onValueChange = { musicFolder = it }, label = { Text("Папка музыки (от корня SMB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Кэш обложек и превью: до 2 ГБ", color = Color.Gray, fontSize = 12.sp)
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Пользователь") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Домен") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { preferences.edit().putString("host", host.trim()).putString("share", shareName.trim()).remove("start_folder").putString("video_folder", videoFolder.trim()).putString("music_folder", musicFolder.trim()).putString("username", username.trim()).putString("password", password).putString("domain", domain.trim()).apply(); connect() }, enabled = !busy) { Text("Сохранить и подключиться") }
                            TextButton(onClick = { showSettings = false }) { Text("Готово", color = AccentColor) }
                        }
                    }
                } else {
                    if (currentPath.isNotBlank()) {
                        Text(currentPath.substringAfterLast('\\'), color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    if (showSearch) {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск среди загруженных файлов…") }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = AccentColor) })
                        Spacer(Modifier.height(10.dp))
                    }
                    val visibleEntries = entries.filter { entry ->
                        (entry.directory || when (mediaSection) {
                            "Видео" -> isVideo(entry.name)
                            "Музыка" -> isAudio(entry.name)
                            else -> true
                        }) && entry.name.contains(searchQuery, ignoreCase = true)
                    }.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
                    if (busy && entries.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentColor) }
                    } else if (visibleEntries.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (searchQuery.isNotBlank()) "Ничего не найдено" else "В этой папке пока нет файлов", color = Color.Gray, fontSize = 16.sp) }
                    } else if (mediaSection == "Видео" && !showSearch) {
                        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 210.dp), modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            gridItems(visibleEntries, key = { it.path }) { entry ->
                                VideoTile(entry = entry, art = mediaArts[entry.path], requestArt = { requestMediaArt(entry, false) }, modifier = if (entry.path == visibleEntries.firstOrNull()?.path) Modifier.focusRequester(contentFocusRequester) else Modifier,
                                    onClick = { if (entry.directory) refresh(entry.path) else play(entry) })
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(visibleEntries, key = { it.path }) { entry ->
                                EntryRow(entry = entry, art = mediaArts[entry.path], requestArt = { if (isAudio(entry.name)) requestMediaArt(entry, true) }, modifier = if (entry.path == visibleEntries.firstOrNull()?.path) Modifier.focusRequester(contentFocusRequester) else Modifier,
                                    onClick = { if (entry.directory) { searchQuery = ""; refresh(entry.path) } else play(entry) })
                            }
                            if (hasMoreEntries) item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    Button(onClick = { loadMore() }, enabled = !busy) { Text(if (busy) "Загружаем…" else "Загрузить ещё") }
                                }
                            }
                        }
                    }
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText!!, color = Color(0xFFFF8D8D), fontSize = 13.sp, maxLines = 2)
                }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, title: String, selected: Boolean, expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0x553F5F91) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick).focusable()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (expanded) 14.dp else 0.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(icon, contentDescription = title, tint = if (selected) AccentColor else Color(0xFFB8C2D2), modifier = Modifier.width(23.dp).height(23.dp))
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(title, color = if (selected) Color.White else Color(0xFFB8C2D2), fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun rememberArtworkBitmap(file: File?): androidx.compose.ui.graphics.ImageBitmap? {
    var bitmap by remember(file?.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(file?.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            if (file == null || !file.isFile) null else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sample = maxOf(1, maxOf(bounds.outWidth / 640, bounds.outHeight / 640))
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
            }
        }
    }
    return bitmap
}

@Composable
private fun EntryRow(entry: RemoteEntry, art: MediaArtResult?, requestArt: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LaunchedEffect(entry.path) { if (!entry.directory && isAudio(entry.name)) requestArt() }
    val artBitmap = rememberArtworkBitmap(art?.image)
    Card(
        modifier = modifier.fillMaxWidth().height(66.dp).clickable(onClick = onClick).focusable(),
        colors = CardDefaults.cardColors(containerColor = PanelColor),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(42.dp).height(42.dp).background(Color(0xFF303030), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (artBitmap != null) {
                    Image(artBitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        imageVector = when {
                            entry.directory -> Icons.Filled.Folder
                            isAudio(entry.name) -> Icons.Filled.AudioFile
                            else -> Icons.Filled.PlayCircleFilled
                        },
                        contentDescription = null,
                        tint = AccentColor,
                        modifier = Modifier.width(23.dp).height(23.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(art?.title?.takeIf { isAudio(entry.name) } ?: entry.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    when {
                        entry.directory -> "ПАПКА"
                        isAudio(entry.name) -> listOfNotNull(art?.artist, art?.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "АУДИО" }
                        else -> entry.name.substringAfterLast('.', "МЕДИА").uppercase()
                    },
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
            }
            Text(if (entry.directory) "ОТКРЫТЬ  ›" else "ВОСПРОИЗВЕСТИ  ›", color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VideoTile(entry: RemoteEntry, art: MediaArtResult?, requestArt: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LaunchedEffect(entry.path) { if (!entry.directory && isVideo(entry.name)) requestArt() }
    val artBitmap = rememberArtworkBitmap(art?.image)
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick).focusable(),
        colors = CardDefaults.cardColors(containerColor = PanelColor), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF252830)), contentAlignment = Alignment.Center) {
                if (artBitmap != null) Image(artBitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(if (entry.directory) Icons.Filled.Folder else Icons.Filled.PlayCircleFilled, null, tint = AccentColor, modifier = Modifier.width(36.dp).height(36.dp))
            }
            Text(entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun isAudio(name: String): Boolean {
    return name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "ape")
}

private fun isVideo(name: String): Boolean {
    return name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "ts", "m2ts", "mpeg", "mpg", "flv")
}
