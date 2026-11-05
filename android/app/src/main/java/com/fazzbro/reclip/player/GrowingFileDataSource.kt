package com.fazzbro.reclip.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.fazzbro.reclip.data.model.DownloadStatus
import com.fazzbro.reclip.data.repository.DownloadRepository
import java.io.File
import java.io.RandomAccessFile

class GrowingFileDataSource(
    private val jobId: String,
    private val repository: DownloadRepository?
) : DataSource {

    class Factory(
        private val jobId: String,
        private val repository: DownloadRepository?
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return GrowingFileDataSource(jobId, repository)
        }
    }

    private var randomAccessFile: RandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var file: File? = null

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: throw IllegalArgumentException("URI path cannot be null")
        val targetFile = File(path)
        file = targetFile

        val raf = RandomAccessFile(targetFile, "r")
        randomAccessFile = raf
        raf.seek(dataSpec.position)

        val isDownloading = isDownloadActive()
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (isDownloading) {
            // Live / growing stream: unbounded length
            C.LENGTH_UNSET.toLong()
        } else {
            (targetFile.length() - dataSpec.position).coerceAtLeast(0L)
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

        while (true) {
            val curLen = file?.length() ?: 0L
            val curPos = raf.filePointer
            val available = curLen - curPos

            if (available > 0) {
                val toRead = minOf(bytesToRead.toLong(), available).toInt()
                val read = raf.read(buffer, offset, toRead)
                if (read > 0) {
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining -= read
                    }
                    return read
                }
            }

            // At current EOF: check if download is still writing new bytes
            if (isDownloadActive()) {
                try {
                    Thread.sleep(80)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return C.RESULT_END_OF_INPUT
                }
                if (file?.exists() != true) {
                    return C.RESULT_END_OF_INPUT
                }
                continue
            } else {
                // Download is finished: read any remaining final bytes
                val finalAvailable = (file?.length() ?: 0L) - raf.filePointer
                if (finalAvailable > 0) {
                    val toRead = minOf(bytesToRead.toLong(), finalAvailable).toInt()
                    val read = raf.read(buffer, offset, toRead)
                    if (read > 0) {
                        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                            bytesRemaining -= read
                        }
                        return read
                    }
                }
                return C.RESULT_END_OF_INPUT
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
    }

    private fun isDownloadActive(): Boolean {
        if (jobId.isBlank() || repository == null) return false
        val item = repository.activeDownloads.value[jobId]
        return item != null && item.status == DownloadStatus.DOWNLOADING
    }
}
