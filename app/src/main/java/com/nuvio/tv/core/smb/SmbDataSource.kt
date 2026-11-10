@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.smb

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

internal class SmbDataSource(
    private val connectionManager: SmbConnectionManager
) : BaseDataSource(false) {
    private var dataSpec: DataSpec? = null
    private var file: SmbRandomAccessFile? = null
    private var fileLength = C.LENGTH_UNSET.toLong()
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var readPosition = 0L

    // The extractor reads in tiny, unpredictable steps while probing and parsing the container,
    // and over SMB every one of those is a full request/response round trip. Fetch a large block
    // at once and serve the small reads from memory instead.
    private var readAheadBuffer: ByteArray? = null
    private var readAheadStart = 0L
    private var readAheadLength = 0

    // Reported on close so a buffering report can be checked against the throughput achieved.
    private var openedAtMs = 0L
    private var bytesReadTotal = 0L

    override fun open(dataSpec: DataSpec): Long {
        if (this.dataSpec != null) close()
        transferInitializing(dataSpec)
        val opened = try {
            connectionManager.openRead(dataSpec.uri.toString())
        } catch (error: Exception) {
            throw IOException("Could not open SMB file", error)
        }
        if (dataSpec.position < 0 || dataSpec.position > opened.length) {
            opened.close()
            throw IOException("SMB read position ${dataSpec.position} is outside the file")
        }
        file = opened
        this.dataSpec = dataSpec
        fileLength = opened.length
        readPosition = dataSpec.position
        openedAtMs = SystemClock.elapsedRealtime()
        bytesReadTotal = 0L
        resetReadAhead()
        val available = (fileLength - readPosition).coerceAtLeast(0L)
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(available, dataSpec.length)
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT
        val opened = file ?: throw IOException("SMB data source is not open")

        // Serve from the block fetched by an earlier call when the range is already in memory.
        val cached = readAheadBuffer
        if (
            cached != null &&
            readPosition >= readAheadStart &&
            readPosition + toRead <= readAheadStart + readAheadLength
        ) {
            System.arraycopy(
                cached,
                (readPosition - readAheadStart).toInt(),
                buffer,
                offset,
                toRead
            )
            readPosition += toRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toRead
            bytesReadTotal += toRead
            bytesTransferred(toRead)
            return toRead
        }

        // A request larger than the read-ahead block is cheaper to answer with one direct read
        // than to refill the block and copy out of it.
        if (toRead >= READ_AHEAD_BYTES) {
            val read = readDirect(opened, buffer, offset, readPosition, toRead)
            if (read <= 0) return C.RESULT_END_OF_INPUT
            readPosition += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesReadTotal += read
            bytesTransferred(read)
            return read
        }

        val target = cached ?: ByteArray(READ_AHEAD_BYTES).also { readAheadBuffer = it }
        val refillLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            READ_AHEAD_BYTES
        } else {
            minOf(READ_AHEAD_BYTES.toLong(), bytesRemaining).toInt()
        }
        val read = readDirect(opened, target, 0, readPosition, refillLength)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        readAheadStart = readPosition
        readAheadLength = read
        val served = minOf(toRead, read)
        System.arraycopy(target, 0, buffer, offset, served)
        readPosition += served
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= served
        bytesReadTotal += served
        bytesTransferred(served)
        return served
    }

    private fun readDirect(
        source: SmbRandomAccessFile,
        buffer: ByteArray,
        offset: Int,
        position: Long,
        length: Int
    ): Int = try {
        source.read(buffer, offset, position, length)
    } catch (error: Exception) {
        throw IOException("Could not read SMB file", error)
    }

    private fun resetReadAhead() {
        readAheadStart = 0L
        readAheadLength = 0
        readAheadBuffer = null
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        val wasOpen = dataSpec != null
        if (wasOpen && bytesReadTotal > 0L) {
            val elapsedMs = (SystemClock.elapsedRealtime() - openedAtMs).coerceAtLeast(1L)
            val megabytes = bytesReadTotal / (1024.0 * 1024.0)
            val mbPerSecond = megabytes / (elapsedMs / 1000.0)
            Log.d(
                TAG,
                "SMB transfer: %.1f MB in %d ms (%.1f MB/s)"
                    .format(megabytes, elapsedMs, mbPerSecond)
            )
        }
        dataSpec = null
        file?.close()
        file = null
        fileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()
        readPosition = 0L
        bytesReadTotal = 0L
        resetReadAhead()
        if (wasOpen) transferEnded()
    }

    class Factory(
        private val connectionManager: SmbConnectionManager
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(connectionManager)
    }

    private companion object {
        const val TAG = "SmbDataSource"

        // 4 MB keeps the number of SMB round trips for a startup burst in the single digits
        // while staying small enough for a 2 GB TV.
        const val READ_AHEAD_BYTES = 4 * 1024 * 1024
    }
}
