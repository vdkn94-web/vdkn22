package com.splayer.splay

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class MediaArtResult(
    val image: File?,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
)

/**
 * Persistent artwork cache (2 GiB max) with two active jobs and two waiting jobs.
 * Source media is staged temporarily because SMB paths are not seekable FFmpeg inputs;
 * temporary source files are deleted as soon as extraction finishes.
 */
class MediaArtCache(
    context: Context,
    private val copyRemoteToPipe: (String, String) -> Unit
) {
    private val cacheDir = File(context.cacheDir, "media-art").apply { mkdirs() }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val executor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2),
        ThreadPoolExecutor.AbortPolicy()
    )

    fun request(entryPath: String, audio: Boolean, callback: (MediaArtResult) -> Unit): Boolean {
        val key = keyFor(entryPath)
        val image = File(cacheDir, "$key.jpg")
        val metadataFile = File(cacheDir, "$key.meta")
        if (audio && metadataFile.isFile) {
            val tags = parseTags(metadataFile.readText())
            image.takeIf { it.isFile && it.length() > 0L }?.setLastModified(System.currentTimeMillis())
            callback(MediaArtResult(image.takeIf { it.isFile && it.length() > 0L }, tags["title"], tags["artist"], tags["album"]))
            return true
        }
        if (image.isFile && image.length() > 0L) {
            image.setLastModified(System.currentTimeMillis())
            callback(MediaArtResult(image))
            return true
        }
        if (!inFlight.add(key)) return true
        try {
            executor.execute {
                val output = File(cacheDir, "$key.jpg")
                var result = MediaArtResult(null)
                try {
                    if (audio) {
                        val probe = withPipe(entryPath) { pipe ->
                            FFprobeKit.execute(
                                "-v error -show_entries format_tags=title,artist,album:stream_tags=title,artist,album -of default=noprint_wrappers=1:nokey=0 ${quote(pipe)}"
                            )
                        }
                        val tags = parseTags(probe.output ?: "")
                        metadataFile.writeText(tags.entries.joinToString("\n") { "${it.key}=${it.value}" })
                        val art = if (output.isFile && output.length() > 0L) {
                            output.setLastModified(System.currentTimeMillis())
                            output
                        } else {
                            val extracted = withPipe(entryPath) { pipe ->
                                FFmpegKit.execute(
                                    "-y -i ${quote(pipe)} -map 0:v:0 -frames:v 1 -vf scale=640:-2 -q:v 3 ${quote(output.absolutePath)}"
                                )
                            }
                            if (ReturnCode.isSuccess(extracted.returnCode) && output.isFile && output.length() > 0L) output else null
                        }
                        result = MediaArtResult(art, tags["title"], tags["artist"], tags["album"])
                    } else {
                        val extracted = withPipe(entryPath) { pipe ->
                            FFmpegKit.execute(
                                "-y -i ${quote(pipe)} -ss 7 -frames:v 1 -vf scale=480:-2 -q:v 3 ${quote(output.absolutePath)}"
                            )
                        }
                        val thumb = if (ReturnCode.isSuccess(extracted.returnCode) && output.isFile && output.length() > 0L) output else null
                        result = MediaArtResult(thumb)
                    }
                    trimCache()
                } catch (_: Exception) {
                    if (output.length() == 0L) output.delete()
                } finally {
                    inFlight.remove(key)
                }
                callback(result)
            }
            return true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            inFlight.remove(key)
            return false
        }
    }

    private fun <T> withPipe(entryPath: String, operation: (String) -> T): T {
        val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
            ?: error("Не удалось создать FFmpeg pipe")
        val writer = Thread {
            try { copyRemoteToPipe(entryPath, pipe) } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        try {
            return operation(pipe)
        } finally {
            writer.interrupt()
            try { writer.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            FFmpegKitConfig.closeFFmpegPipe(pipe)
            try { writer.join(750) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun parseTags(output: String): Map<String, String> {
        val result = HashMap<String, String>()
        output.lineSequence().forEach { line ->
            val split = line.indexOf('=')
            if (split > 0) {
                val tag = line.substring(0, split).trim().lowercase().substringAfterLast(':')
                result[tag] = line.substring(split + 1).trim().trim('"').takeIf { it.isNotBlank() } ?: ""
            }
        }
        return result
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun keyFor(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
