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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
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

    fun list(path: String): List<RemoteEntry> {
        val activeShare = share ?: error("Сначала подключись к SMB-папке")
        return activeShare.list(path).mapNotNull { item ->
            val name = item.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDirectory = EnumWithValue.EnumUtils.isSet(
                item.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            )
            val childPath = if (path.isBlank()) name else "$path\\$name"
            RemoteEntry(name, childPath, isDirectory)
        }.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun disconnect() {
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

    var host by rememberSaveable { mutableStateOf("") }
    var shareName by rememberSaveable { mutableStateOf("") }
    val preferences = remember { context.getSharedPreferences("splay_settings", android.content.Context.MODE_PRIVATE) }
    var startFolder by rememberSaveable { mutableStateOf(preferences.getString("start_folder", "") ?: "") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
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
                val result = withContext(Dispatchers.IO) { browser.list(path) }
                entries = result
                currentPath = path
            } catch (e: Exception) {
                errorText = e.message ?: "Не удалось прочитать папку"
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
                val initialEntries = withContext(Dispatchers.IO) {
                    browser.connect(host, shareName, username, password, domain)
                    browser.list(initialPath)
                }
                entries = initialEntries
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
        Row(modifier = Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SPlay", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-1).sp)
                    Spacer(Modifier.width(14.dp))
                    Text("MEDIA PLAYER", color = AccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(20.dp))

                if (!connected) {
                    Text("Подключение к серверу", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(host, { host = it }, label = { Text("IP или имя сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(shareName, { shareName = it }, label = { Text("Общая папка (share), например Media") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(startFolder, { startFolder = it; preferences.edit().putString("start_folder", it).apply() }, label = { Text("Стартовая папка внутри share (необязательно)") }, placeholder = { Text("Например: Media/Movies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(username, { username = it }, label = { Text("Пользователь (можно пусто для guest)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(domain, { domain = it }, label = { Text("Домен (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { connect() }, enabled = !busy) {
                        Text(if (busy) "Подключаемся…" else "Подключиться")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("SMB2 / SMB3  •  Пустой путь откроет корень ресурса.", color = Color.LightGray, fontSize = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SPlay", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(if (currentPath.isBlank()) "Корень общей папки" else currentPath.replace("\\", "  /  "), color = Color.LightGray, fontSize = 13.sp)
                        }
                        TextButton(onClick = {
                            browser.disconnect()
                            connected = false
                            entries = emptyList()
                            currentPath = ""
                            nowPlaying = null
                        }) { Text("Отключиться") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (currentPath.isNotBlank()) {
                        TextButton(onClick = { refresh(currentPath.substringBeforeLast('\\', "")) }) {
                            Text("← На уровень выше")
                        }
                    }
                    if (busy) {
                        CircularProgressIndicator()
                    } else if (entries.isEmpty()) {
                        Text("В этой папке пока нет файлов", color = Color.LightGray)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(entries, key = { it.path }) { entry ->
                                EntryRow(entry = entry, onClick = {
                                    if (entry.directory) refresh(entry.path) else play(entry)
                                })
                            }
                        }
                    }
                }

                if (errorText != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorText!!, color = Color(0xFFFF8D8D), fontSize = 14.sp)
                }
            }

            if (nowPlaying != null) {
                Column(modifier = Modifier.weight(1.05f).fillMaxSize()) {
                    Text("NOW PLAYING", color = AccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(nowPlaying!!.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(14.dp))
                    if (videoMode) {
                        AndroidView(
                            factory = { viewContext ->
                                VLCVideoLayout(viewContext).also { layout ->
                                    videoLayout = layout
                                    mediaPlayer.attachViews(layout, null, false, false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().background(PanelColor, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("S", fontSize = 112.sp, color = AccentColor, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (playbackPlaying) mediaPlayer.pause() else mediaPlayer.play()
                            playbackPlaying = !playbackPlaying
                        }) { Text(if (playbackPlaying) "Пауза" else "Продолжить") }
                        Button(onClick = {
                            mediaPlayer.stop()
                            nowPlaying = null
                            videoMode = false
                            playbackPlaying = false
                        }) { Text("Стоп") }
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1.05f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(250.dp).background(PanelColor, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("♫", fontSize = 76.sp, color = AccentColor)
                            Text("SPlay", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Твоя музыка и видео", color = Color.LightGray, fontSize = 16.sp)
                        }
                    }
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
