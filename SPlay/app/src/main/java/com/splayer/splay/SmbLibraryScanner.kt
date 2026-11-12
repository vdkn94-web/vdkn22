package com.splayer.splay

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class SmbLibraryScanner {
    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "ts",
        "m2ts", "mts", "webm", "flv", "3gp", "3g2", "vob", "ogv", "divx",
        "asf", "rm", "rmvb", "m2v", "mxf", "wtv"
    )
    private val musicExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape",
        "alac", "aiff", "aif", "aifc", "mka", "mid", "midi", "dsf", "dff",
        "wv", "tta", "ac3", "eac3", "dts", "amr"
    )

    suspend fun scan(
        settings: SmbSettings,
        kind: MediaKind,
        onProgress: (visitedFolders: Int, foundFiles: Int) -> Unit
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        require(settings.isConfigured()) { "Заполните сервер и общую папку SMB" }
        val extensions = if (kind == MediaKind.VIDEO) videoExtensions else musicExtensions
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        val results = ArrayList<MediaItem>()
        val folders = ArrayDeque<String>()
        val visited = HashSet<String>()
        val startFolder = settings.folder.trim().replace('/', '\\').trim('\\')
        folders.add(startFolder)

        try {
            client.connect(settings.server.trim()).use { connection ->
                connection.authenticate(settings.authenticationContext()).use { session ->
                    (session.connectShare(settings.share.trim()) as DiskShare).use { diskShare ->
                        while (folders.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            if (visited.size >= MAX_FOLDERS || results.size >= MAX_FILES) break
                            val current = folders.removeFirst()
                            if (!visited.add(current)) continue
                            val entries = diskShare.list(current)
                            for (entry in entries) {
                                val name = entry.fileName
                                if (name == "." || name == ".." || name.isBlank()) continue
                                val child = if (current.isBlank()) name else "$current\\$name"
                                val isDirectory = entry.fileAttributes.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                                if (isDirectory) {
                                    if (visited.size + folders.size < MAX_FOLDERS) folders.addLast(child)
                                } else {
                                    val extension = name.substringAfterLast('.', "").lowercase()
                                    if (extension in extensions) {
                                        results += MediaItem(child, name, kind)
                                        if (results.size >= MAX_FILES) break
                                    }
                                }
                            }
                            onProgress(visited.size, results.size)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SPlayLog.error("SMB scan", "Scan failed in ${visited.size} folders", error)
            throw error
        } finally {
            runCatching { client.close() }
        }
        results.sortBy { it.name.lowercase() }
        results
    }

    suspend fun testConnection(settings: SmbSettings) = withContext(Dispatchers.IO) {
        require(settings.server.isNotBlank() && settings.share.isNotBlank()) {
            "Укажите сервер и общую папку SMB"
        }
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        try {
            client.connect(settings.server.trim()).use { connection ->
                connection.authenticate(settings.authenticationContext()).use { session ->
                    (session.connectShare(settings.share.trim()) as DiskShare).use { share ->
                        if (settings.folder.isNotBlank()) {
                            require(share.folderExists(settings.folder.trim().replace('/', '\\'))) {
                                "Папка не найдена: ${settings.folder}"
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        private const val MAX_FOLDERS = 30_000
        private const val MAX_FILES = 100_000
    }
}
