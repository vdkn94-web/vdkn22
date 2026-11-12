package com.splayer.splay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val scanning: Boolean = false,
    val visitedFolders: Int = 0,
    val foundFiles: Int = 0,
    val message: String = ""
)

class MediaLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = SmbLibraryScanner()
    private val database = MediaCatalogDatabase(application)
    private val _video = MutableStateFlow(LibraryState())
    private val _music = MutableStateFlow(LibraryState())
    val video: StateFlow<LibraryState> = _video.asStateFlow()
    val music: StateFlow<LibraryState> = _music.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                database.read(MediaKind.VIDEO) to database.read(MediaKind.MUSIC)
            }
            _video.value = LibraryState(items = cached.first, message = cached.first.takeIf { it.isNotEmpty() }?.let { "Сохранённая библиотека: ${it.size}" } ?: "")
            _music.value = LibraryState(items = cached.second, message = cached.second.takeIf { it.isNotEmpty() }?.let { "Сохранённая библиотека: ${it.size}" } ?: "")
            if (loadSettings().isConfigured()) scanAll()
        }
    }

    fun scanAll() {
        scan(MediaKind.VIDEO)
        scan(MediaKind.MUSIC)
    }

    fun scan(kind: MediaKind) {
        val state = if (kind == MediaKind.VIDEO) _video else _music
        if (state.value.scanning) return
        state.value = state.value.copy(scanning = true, visitedFolders = 0, foundFiles = 0, message = "Сканирование…")
        viewModelScope.launch {
            try {
                SPlayLog.info("Library", "${kind.name}: scan started")
                val items = scanner.scan(loadSettings(), kind) { folders, files ->
                    state.value = state.value.copy(visitedFolders = folders, foundFiles = files)
                }
                withContext(Dispatchers.IO) { database.replace(kind, items) }
                state.value = LibraryState(items = items, message = "Найдено файлов: ${items.size}")
                SPlayLog.info("Library", "${kind.name}: scan complete, ${items.size} files")
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(scanning = false, message = "Сканирование отменено")
                throw cancelled
            } catch (error: Exception) {
                state.value = state.value.copy(scanning = false, message = error.message ?: "Ошибка сканирования")
                SPlayLog.error("Library", "${kind.name}: scan failed", error)
            }
        }
    }

    fun testConnection(onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { scanner.testConnection(loadSettings()) }
                .onSuccess {
                    SPlayLog.info("SMB", "Connection test succeeded")
                    onResult("Подключение успешно")
                }
                .onFailure {
                    SPlayLog.error("SMB", "Connection test failed", it)
                    onResult(it.message ?: "Не удалось подключиться")
                }
        }
    }

    private fun loadSettings() = SmbSettings(
        server = SecurePreferences.get("server"),
        share = SecurePreferences.get("share"),
        username = SecurePreferences.get("username"),
        password = SecurePreferences.getSecret("password"),
        folder = SecurePreferences.get("folder")
    )

    override fun onCleared() {
        database.close()
        super.onCleared()
    }
}
