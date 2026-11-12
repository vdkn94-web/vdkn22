package com.splayer.splay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.context = applicationContext
        SPlayLog.install(applicationContext)
        SPlayLog.info("App", "SPlay 2.0 started")
        setContent {
            MaterialTheme {
                SPlayApp(onExportLogs = { createLogExport() })
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
                    SPlayLog.file(applicationContext).inputStream().use { input -> input.copyTo(output) }
                }
                SPlayLog.info("Logs", "Diagnostic log exported")
            }.onFailure { SPlayLog.error("Logs", "Log export failed", it) }
        }
    }
}

@Composable
private fun SPlayApp(onExportLogs: () -> Unit) {
    var selected by remember { mutableStateOf(Section.VIDEO) }
    var settingsTab by remember { mutableStateOf("Подключение") }
    var search by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(SecurePreferences.get("server")) }
    var share by remember { mutableStateOf(SecurePreferences.get("share")) }
    var folder by remember { mutableStateOf(SecurePreferences.get("folder")) }
    var username by remember { mutableStateOf(SecurePreferences.get("username")) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Укажите SMB-сервер и папку с файлами") }

    BackHandler(enabled = selected != Section.VIDEO) { selected = Section.VIDEO }

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
                MenuButton(section, selected == section) { selected = section }
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
                        Spacer(Modifier.height(14.dp))
                        Text("Список видеоклипов появится после настройки SMB", color = Color(0xFFADB5C2))
                        Spacer(Modifier.height(12.dp))
                        Text(status, color = Color(0xFF8F98A6), fontSize = 14.sp)
                    }
                    Section.MUSIC -> {
                        Text("Музыка", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Text("Список музыкальных треков появится после настройки SMB", color = Color(0xFFADB5C2))
                    }
                    Section.SEARCH -> {
                        Text("Поиск", fontSize = 30.sp, color = Color.White)
                        Spacer(Modifier.height(18.dp))
                        AppInput(search, { search = it }, "Название файла")
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
                                AppInput(username, { username = it }, "Логин")
                                AppInput(password, { password = it }, "Пароль", secret = true)
                                Button(onClick = {
                                    SecurePreferences.put("server", server)
                                    SecurePreferences.put("share", share)
                                    SecurePreferences.put("username", username)
                                    if (password.isNotEmpty()) SecurePreferences.putSecret("password", password)
                                    password = ""
                                    status = "Параметры подключения сохранены"
                                    SPlayLog.info("Settings", "SMB connection settings saved")
                                }) { Text("Сохранить подключение") }
                            }
                            "Папка к файлам" -> {
                                AppInput(folder, { folder = it }, "Путь внутри общей папки")
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

@Composable
private fun MenuButton(section: Section, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp).onFocusChanged { focused = it.isFocused },
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
                .background(Color(0xFF252B34)).padding(14.dp)
        )
    }
}
