package org.nextcolor.dashcamviewer.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.nextcolor.dashcamviewer.protocol.TransferSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

data class OwnedDownload(val uri: String, val name: String, val mimeType: String, val createdAt: Long)

/** Creates one app-owned IS_PENDING row and publishes it only after signature and length checks. */
class MediaStoreTransferSink(
    context: Context,
    displayName: String,
    private val expectedLength: Long?,
    private val onPendingUri: (String?) -> Unit,
    private val onPublished: suspend (OwnedDownload) -> Unit,
) : TransferSink {
    private val resolver = context.applicationContext.contentResolver
    private val extension = displayName.substringAfterLast('.', "").lowercase()
    private val isImage = extension == "jpg"
    private val mime = if (isImage) "image/jpeg" else when (extension) { "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "ts" -> "video/mp2t"; else -> error("Unsupported media extension") }
    private val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val relativePath = if (isImage) "Pictures/DashcamViewer" else "Movies/DashcamViewer"
    private val name = collisionSafeName(displayName)
    private val uri: Uri
    private val output: OutputStream
    private var bytes = 0L
    private val prefix = ByteArray(64)
    private var prefixSize = 0
    private var finished = false

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = resolver.insert(collection, values) ?: error("Could not create a pending media row")
        onPendingUri(uri.toString())
        output = resolver.openOutputStream(uri, "w") ?: run { resolver.delete(uri, null, null); error("Could not open pending media row") }
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        check(!finished)
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val copyCount = minOf(length, prefix.size - prefixSize)
        if (copyCount > 0) { bytes.copyInto(prefix, prefixSize, offset, offset + copyCount); prefixSize += copyCount }
        output.write(bytes, offset, length)
        this@MediaStoreTransferSink.bytes += length
    }

    override suspend fun commit() = withContext(Dispatchers.IO) {
        check(!finished)
        if (expectedLength != null && bytes != expectedLength) throw IllegalStateException("Incomplete download")
        if (!validSignature()) throw IllegalStateException("Downloaded file signature did not match its type")
        output.flush(); output.close()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(uri, values, null, null) != 1) throw IllegalStateException("Could not publish completed file")
        finished = true
        onPublished(OwnedDownload(uri.toString(), name, mime, System.currentTimeMillis()))
        onPendingUri(null)
    }

    override suspend fun rollback() = withContext(Dispatchers.IO) {
        if (!finished) {
            finished = true
            runCatching { output.close() }
            val deleted = runCatching { resolver.delete(uri, null, null); true }.getOrDefault(false)
            if (deleted) onPendingUri(null)
        }
    }

    fun pendingUri(): Uri = uri
    private fun validSignature(): Boolean {
        val p = prefix.copyOf(prefixSize)
        return when (extension) {
            "jpg" -> p.size >= 3 && p[0] == 0xff.toByte() && p[1] == 0xd8.toByte() && p[2] == 0xff.toByte()
            "ts" -> p.isNotEmpty() && p[0] == 0x47.toByte()
            "mp4", "mov" -> p.size >= 12 && String(p, 4, 4, Charsets.US_ASCII) == "ftyp"
            else -> false
        }
    }
    private fun collisionSafeName(original: String): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        return "$base-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}$ext"
    }
}
