package com.splayer.splay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.math.max

class SmbArtworkRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "music-artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 12L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun get(settings: SmbSettings, relativePath: String): Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = sha256(relativePath)
            synchronized(memory) { memory.get(key) }?.let { return@withContext it }
            val cached = File(cacheDir, "$key.jpg")
            if (cached.isFile) {
                val decoded = BitmapFactory.decodeFile(cached.absolutePath)
                if (decoded != null) {
                    synchronized(memory) { memory.put(key, decoded) }
                    return@withContext decoded
                }
                cached.delete()
            }

            val retriever = MediaMetadataRetriever()
            var remote: RemoteArtworkSource? = null
            val bitmap = try {
                remote = RemoteArtworkSource(settings, relativePath)
                retriever.setDataSource(remote)
                val embedded = retriever.embeddedPicture ?: return@withContext null
                decodeCover(embedded)
            } catch (error: Exception) {
                SPlayLog.warn("Artwork", "Could not read artwork from $relativePath", error)
                null
            } finally {
                runCatching { retriever.release() }
                runCatching { remote?.close() }
            }

            if (bitmap != null) {
                runCatching {
                    cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                    trimDiskCache()
                }.onFailure { SPlayLog.warn("Artwork", "Artwork cache write failed", it) }
                synchronized(memory) { memory.put(key, bitmap) }
            }
            bitmap
        }

    private fun decodeCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 720) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        )
    }

    private fun trimDiskCache() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_CACHE) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private class RemoteArtworkSource(
        settings: SmbSettings,
        relativePath: String
    ) : MediaDataSource() {
        private val client = SMBClient(SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build())
        private val connection = client.connect(settings.server.trim())
        private val session = connection.authenticate(settings.authenticationContext())
        private val share = session.connectShare(settings.share.trim()) as DiskShare
        private val file = share.openFile(
            relativePath.replace('/', '\\').trim('\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        private val length = file.length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            val requested = minOf(size.toLong(), length - position).toInt()
            return file.read(buffer, position, offset, requested)
        }

        override fun getSize(): Long = length

        override fun close() {
            runCatching { file.close() }
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    companion object {
        private const val MAX_DISK_CACHE = 160L * 1024L * 1024L
    }
}
