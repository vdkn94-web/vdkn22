package com.splayer.splay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.EOFException
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbDataSourceFactory(
    private val settingsProvider: () -> SmbSettings
) : androidx.media3.datasource.DataSource.Factory {
    override fun createDataSource(): androidx.media3.datasource.DataSource =
        SmbDataSource(settingsProvider())
}

class SmbDataSource(
    private val settings: SmbSettings
) : BaseDataSource(true) {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var remoteFile: com.hierynomus.smbj.share.File? = null
    private var currentUri: Uri? = null
    private var currentPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var transferOpen = false
    private val readAhead = ByteArray(READ_AHEAD_BYTES)
    private var readAheadOffset = 0
    private var readAheadLength = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val segments = uri.pathSegments
        if (uri.scheme != "smb" || uri.host.isNullOrBlank() || segments.size < 2) {
            throw IOException("Некорректный SMB-путь")
        }
        val server = uri.host!!
        val shareName = segments.first()
        val path = segments.drop(1).joinToString("\\")
        try {
            val smbClient = SMBClient(
                SmbConfig.builder()
                    .withTimeout(60, TimeUnit.SECONDS)
                    .withSoTimeout(60, TimeUnit.SECONDS)
                    .withReadBufferSize(READ_AHEAD_BYTES)
                    .build()
            )
            client = smbClient
            val smbConnection = smbClient.connect(server)
            connection = smbConnection
            val smbSession = smbConnection.authenticate(settings.authenticationContext())
            session = smbSession
            val diskShare = smbSession.connectShare(shareName) as DiskShare
            share = diskShare
            val file = diskShare.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            remoteFile = file
            val fileLength = file.length
            currentPosition = dataSpec.position
            if (currentPosition > fileLength) throw EOFException("Позиция чтения за концом файла")
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                fileLength - currentPosition
            } else {
                minOf(dataSpec.length, fileLength - currentPosition)
            }
            readAheadOffset = 0
            readAheadLength = 0
            currentUri = uri
            transferStarted(dataSpec)
            transferOpen = true
            return bytesRemaining
        } catch (error: Exception) {
            closeResources()
            if (error is IOException) throw error
            throw IOException("Не удалось открыть SMB-файл: ${error.message}", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val file = remoteFile ?: throw IOException("SMB-файл не открыт")

        if (readAheadOffset >= readAheadLength) {
            val requested = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                readAhead.size
            } else {
                minOf(readAhead.size.toLong(), bytesRemaining).toInt()
            }
            val count = try {
                file.read(readAhead, currentPosition, 0, requested)
            } catch (error: Exception) {
                throw IOException("Ошибка чтения SMB-файла", error)
            }
            if (count <= 0) {
                bytesRemaining = 0
                return C.RESULT_END_OF_INPUT
            }
            currentPosition += count
            readAheadOffset = 0
            readAheadLength = count
        }

        val available = readAheadLength - readAheadOffset
        val copied = minOf(length, available, if (bytesRemaining == C.LENGTH_UNSET.toLong()) Int.MAX_VALUE else bytesRemaining.toInt())
        System.arraycopy(readAhead, readAheadOffset, buffer, offset, copied)
        readAheadOffset += copied
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= copied
        bytesTransferred(copied)
        return copied
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        closeResources()
        readAheadOffset = 0
        readAheadLength = 0
        if (transferOpen) {
            transferOpen = false
            transferEnded()
        }
    }

    private fun closeResources() {
        runCatching { remoteFile?.close() }
        remoteFile = null
        runCatching { share?.close() }
        share = null
        runCatching { session?.close() }
        session = null
        runCatching { connection?.close() }
        connection = null
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private const val READ_AHEAD_BYTES = 1024 * 1024
    }
}

object SmbMediaUri {
    fun create(settings: SmbSettings, relativePath: String): Uri {
        val encodedPath = Uri.encode(relativePath.replace("\\", "/"), "/")
        return Uri.parse("smb://${settings.server}/${Uri.encode(settings.share)}/$encodedPath")
    }
}
