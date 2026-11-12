package com.splayer.splay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private enum class Section(val title: String, val icon: String) {
    VIDEO("Видео", "▶"),
    MUSIC("Музыка", "♫"),
    SEARCH("Поиск", "⌕"),
    SETTINGS("Настройки", "⚙")
}

class MainActivity : ComponentActivity() {
    private val libraryViewModel: MediaLibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.context = applicationContext
        SPlayLog.install(applicationContext)
        SPlayLog.info("App", "SPlay 2.0 started")
        setContent {
            MaterialTheme {
                SPlayApp(libraryViewModel, onExportLogs = { createLogExport() })
            }
        }
    }

    private fun createLogExport() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TITLE, "splay-logs.txt")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 4102)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    SPlayLog.exportTo(applicationContext, output)
                }
                SPlayLog.info("Logs", "Diagnostic log exported")
            }.onFailure { SPlayLog.error("Logs", "Log export failed", it) }
        }
    }
}

@Composable
private fun SPlayApp(viewModel: MediaLibraryViewModel, onExportLogs: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(Section.VIDEO) }
    var settingsTab by remember { mutableStateOf("Подключение") }
    var search by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(SecurePreferences.get("server")) }
    var share by remember { mutableStateOf(SecurePreferences.get("share")) }
    var folder by remember { mutableStateOf(SecurePreferences.get("folder")) }
    var username by remember { mutableStateOf(SecurePreferences.get("username")) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Укажите SMB-сервер и папку с файлами") }
    var activeItem by remember { mutableStateOf<MediaItem?>(null) }
    var queue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val videoState by viewModel.video.collectAsState()
    val musicState by viewModel.music.collectAsState()
    val menuFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { menuFocus.requestFocus() } }

    BackHandler(enabled = activeItem != null || selected != Section.VIDEO) {
        if (activeItem != null) activeItem = null else selected = Section.VIDEO
    }

    DisposableEffect(activeItem?.path) {
        val item = activeItem
        if (item == null) {
            player = null
            onDispose { }
        } else {
            val settings = currentSmbSettings()
            try {
                val exo = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSourceFactory { settings }))
                    .build()
                exo.setMediaItem(androidx.media3.common.MediaItem.fromUri(SmbMediaUri.create(settings, item.path)))
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && queueIndex < queue.lastIndex) {
                            queueIndex++
                            activeItem = queue[queueIndex]
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        SPlayLog.error("Player", "Playback failed for ${item.path}", error)
                    }
                }
                exo.addListener(listener)
                exo.prepare()
                exo.playWhenReady = true
                player = exo
                SPlayLog.info("Player", "Opening ${item.path}")
                onDispose {
                    exo.removeListener(listener)
                    exo.release()
                    if (player === exo) player = null
                }
            } catch (error: Exception) {
                SPlayLog.error("Player", "Could not create player", error)
                status = error.message ?: "Не удалось запустить плеер"
                activeItem = null
                onDispose { }
            }
        }
    }

    if (activeItem != null && player != null) {
        val currentSettings = remember(activeItem?.path) { currentSmbSettings() }
        PlaybackScreen(
            context = context,
            item = activeItem!!,
            player = player!!,
            settings = currentSettings,
            onPrevious = {
                if (queueIndex > 0) {
                    queueIndex--
                    activeItem = queue[queueIndex]
                }
            },
            onNext = {
                if (queueIndex < queue.lastIndex) {
                    queueIndex++
                    activeItem = queue[queueIndex]
                }
            }
        )
        return
    }

    Row(
        Modifier.fillMaxSize().background(Color(0xFF101216)).padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column(
            Modifier.width(220.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("SPLAY", fontSize = 25.sp, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Section.entries.forEach { section ->
                MenuButton(
                    section,
                    selected == section,
                    onClick = { selected = section },
                    focusModifier = if (section == Section.VIDEO) Modifier.focusRequester(menuFocus) else Modifier
                )
            }
            Spacer(Modifier.weight(1f))
            Text("SPlay 2.0", color = Color(0xFF777E8B), fontSize = 12.sp)
        }

        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(22.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF191D24))
        ) {
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                when (selected) {
                    Section.VIDEO -> {
                        Text("Видео", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Button(onClick = { viewModel.scan(MediaKind.VIDEO) }, enabled = !videoState.scanning) {
                                Text(if (videoState.scanning) "Сканирование…" else "Сканировать видео")
                            }
                            Text(
                                if (videoState.scanning) "Папок: ${videoState.visitedFolders} · Файлов: ${videoState.foundFiles}"
                                else videoState.message.ifBlank { status },
                                color = Color(0xFFADB5C2),
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        MediaList(videoState.items, "Видео пока не найдены") { item ->
                            queue = videoState.items
                            queueIndex = videoState.items.indexOf(item)
                            activeItem = item
                        }
                    }
                    Section.MUSIC -> {
                        Text("Музыка", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Button(onClick = { viewModel.scan(MediaKind.MUSIC) }, enabled = !musicState.scanning) {
                                Text(if (musicState.scanning) "Сканирование…" else "Сканировать музыку")
                            }
                            Text(
                                if (musicState.scanning) "Папок: ${musicState.visitedFolders} · Треков: ${musicState.foundFiles}"
                                else musicState.message,
                                color = Color(0xFFADB5C2),
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        MediaList(musicState.items, "Музыкальные треки пока не найдены") { item ->
                            queue = musicState.items
                            queueIndex = musicState.items.indexOf(item)
                            activeItem = item
                        }
                    }
                    Section.SEARCH -> {
                        Text("Поиск", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(18.dp))
                        AppInput(search, { search = it }, "Название файла")
                        val found = (videoState.items + musicState.items).filter {
                            it.name.contains(search, ignoreCase = true)
                        }
                        MediaList(found, "Ничего не найдено") { item ->
                            queue = found
                            queueIndex = found.indexOf(item)
                            activeItem = item
                        }
                    }
                    Section.SETTINGS -> {
                        Text("Настройки", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Подключение", "Папка к файлам", "Логи").forEach { tab ->
                                Button(onClick = { settingsTab = tab }) {
                                    Text(if (settingsTab == tab) "• $tab" else tab)
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        when (settingsTab) {
                            "Подключение" -> {
                                AppInput(server, { server = it }, "Сервер (например, 192.168.1.10)")
                                AppInput(share, { share = it }, "Общая папка SMB")
                                AppInput(username, { username = it }, "Логин (можно Guest)")
                                AppInput(password, { password = it }, "Пароль", secret = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = {
                                        saveConnection(server, share, username, password)
                                        password = ""
                                        status = "Параметры подключения сохранены"
                                        SPlayLog.info("Settings", "SMB connection settings saved")
                                    }) { Text("Сохранить подключение") }
                                    Button(onClick = {
                                        saveConnection(server, share, username, password)
                                        password = ""
                                        viewModel.testConnection { status = it }
                                    }) { Text("Проверить соединение") }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(status, color = Color(0xFFADB5C2), fontSize = 14.sp)
                            }
                            "Папка к файлам" -> {
                                AppInput(folder, { folder = it }, "Путь внутри общей папки (пусто — корень)")
                                Button(onClick = {
                                    SecurePreferences.put("folder", folder)
                                    status = "Папка сканирования сохранена"
                                    SPlayLog.info("Settings", "Scan folder saved")
                                }) { Text("Сохранить папку") }
                            }
                            "Логи" -> {
                                Text("Журнал ошибок и событий приложения", color = Color(0xFFADB5C2))
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = onExportLogs) { Text("Сохранить логи в TXT") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun currentSmbSettings() = SmbSettings(
    server = SecurePreferences.get("server"),
    share = SecurePreferences.get("share"),
    username = SecurePreferences.get("username"),
    password = SecurePreferences.getSecret("password"),
    folder = SecurePreferences.get("folder")
)

private fun saveConnection(server: String, share: String, username: String, password: String) {
    SecurePreferences.put("server", server)
    SecurePreferences.put("share", share)
    SecurePreferences.put("username", username)
    if (password.isNotEmpty()) SecurePreferences.putSecret("password", password)
}

@Composable
private fun MediaList(items: List<MediaItem>, emptyMessage: String, onItemClick: (MediaItem) -> Unit) {
    if (items.isEmpty()) {
        Text(emptyMessage, color = Color(0xFF8F98A6), fontSize = 15.sp)
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(items, key = { it.path }) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF222832))
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (item.kind == MediaKind.VIDEO) "▶" else "♫", color = Color(0xFF93B9E2))
                        Spacer(Modifier.width(12.dp))
                        Text(item.name, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuButton(
    section: Section,
    selected: Boolean,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp).then(focusModifier).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = when {
                selected -> Color(0xFF294E76)
                focused -> Color(0xFF303844)
                else -> Color.Transparent
            }
        )
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(section.icon, color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Text(section.title, color = Color.White, fontSize = 17.sp)
        }
    }
}

@Composable
private fun AppInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    secret: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, color = Color(0xFFADB5C2), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (focused) Color(0xFF35445A) else Color(0xFF252B34))
                .onFocusChanged { focused = it.isFocused }
                .padding(14.dp)
        )
    }
}
