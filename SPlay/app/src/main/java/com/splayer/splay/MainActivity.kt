package com.splayer.splay

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private val AppBackground = Color(0xFF171717)
private val PanelColor = Color(0xFF242424)
private val AccentColor = Color(0xFFFF8800)

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
    var startFolder by rememberSaveable { mutableStateOf(preferences.getString("start_folder", "") ?: "") }
    var username by rememberSaveable { mutableStateOf(preferences.getString("username", "") ?: "") }
    var password by rememberSaveable { mutableStateOf(preferences.getString("password", "") ?: "") }
    var domain by rememberSaveable { mutableStateOf(preferences.getString("domain", "") ?: "") }
    var mediaSection by rememberSaveable { mutableStateOf("Все") }
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
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

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
                val initialPath = startFolder.trim().replace("/", "\\").trim('\\')
                preferences.edit().putString("host", host.trim()).putString("share", shareName.trim()).putString("start_folder", startFolder.trim()).putString("username", username.trim()).putString("password", password).putString("domain", domain.trim()).apply()
                val initialEntries = withContext(Dispatchers.IO) {
                    browser.connect(host, shareName, username, password, domain)
                    browser.listPage(initialPath, true)
                }
                entries = initialEntries.first
                hasMoreEntries = initialEntries.second
                currentPath = initialPath
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
            nowPlaying = entry
            videoMode = isVideo(entry.name)
            mediaPlayer.play()
            playbackPlaying = true
        } catch (e: Exception) {
            errorText = "VLC не смог открыть файл: ${e.message ?: "ошибка воспроизведения"}"
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

    BackHandler(enabled = nowPlaying != null || (connected && currentPath.isNotBlank())) {
        if (nowPlaying != null) {
            mediaPlayer.stop()
            nowPlaying = null
            videoMode = false
            playbackPlaying = false
        } else {
            val parent = currentPath.substringBeforeLast('\\', "")
            refresh(parent)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        if (nowPlaying != null) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(Modifier.fillMaxWidth().background(PanelColor).padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SPlay", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(16.dp))
                    Text(nowPlaying!!.name, modifier = Modifier.weight(1f), color = Color.LightGray, fontSize = 16.sp, maxLines = 1)
                    TextButton(onClick = {
                        mediaPlayer.stop()
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
                            Text("♫", fontSize = 88.sp, color = AccentColor)
                            Text(nowPlaying!!.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("Воспроизведение аудио", color = Color.LightGray, fontSize = 14.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(PanelColor).padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (playbackPlaying) mediaPlayer.pause() else mediaPlayer.play()
                        playbackPlaying = !playbackPlaying
                    }) { Text(if (playbackPlaying) "Пауза" else "Продолжить") }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = {
                        mediaPlayer.stop()
                        nowPlaying = null
                        videoMode = false
                        playbackPlaying = false
                    }) { Text("Остановить") }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SPlay", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    Spacer(Modifier.width(12.dp))
                    Text("MEDIA PLAYER", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    if (connected) {
                        Text("● ПОДКЛЮЧЕНО", color = Color(0xFF8BCB9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(14.dp))
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { browser.disconnect() }
                            connected = false
                            entries = emptyList()
                            hasMoreEntries = false
                            currentPath = ""
                        }) { Text("Отключиться", color = AccentColor) }
                    }
                }
                Spacer(Modifier.height(18.dp))
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
                        OutlinedTextField(startFolder, { startFolder = it; preferences.edit().putString("start_folder", it).apply() }, label = { Text("Начальная папка (необязательно)") }, placeholder = { Text("Movies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                } else {
                    Row(Modifier.fillMaxWidth().background(PanelColor, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (currentPath.isBlank()) shareName else currentPath.replace("\\", "  /  "), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("Файловая библиотека", color = Color.LightGray, fontSize = 12.sp)
                        }
                        if (currentPath.isNotBlank()) TextButton(onClick = { refresh(currentPath.substringBeforeLast('\\', "")) }) { Text("← Назад", color = AccentColor) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Все", "Видео", "Музыка").forEach { section ->
                            val selected = mediaSection == section
                            Surface(color = if (selected) AccentColor.copy(alpha = 0.18f) else PanelColor, shape = RoundedCornerShape(9.dp), modifier = Modifier.clickable { mediaSection = section }.focusable()) {
                                Text(section, color = if (selected) AccentColor else Color.LightGray, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${entries.size}${if (hasMoreEntries) "+" else ""} элементов", color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск в текущей папке…") }, leadingIcon = { Text("⌕", color = AccentColor, fontSize = 22.sp) })
                    Spacer(Modifier.height(10.dp))
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
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(visibleEntries, key = { it.path }) { entry ->
                                EntryRow(entry = entry, onClick = {
                                    if (entry.directory) {
                                        searchQuery = ""
                                        refresh(entry.path)
                                    } else play(entry)
                                })
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

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).focusable(),
        colors = CardDefaults.cardColors(containerColor = PanelColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (entry.directory) "📁" else if (isAudio(entry.name)) "♫" else "▶", fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Text(entry.name, modifier = Modifier.weight(1f), color = Color.White, fontSize = 17.sp, maxLines = 1)
            Text(if (entry.directory) "ОТКРЫТЬ  ›" else "ВОСПРОИЗВЕСТИ", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun isAudio(name: String): Boolean {
    return name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "ape")
}

private fun isVideo(name: String): Boolean {
    return name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "ts", "m2ts", "mpeg", "mpg", "flv")
}
