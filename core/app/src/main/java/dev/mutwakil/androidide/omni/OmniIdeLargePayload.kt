package dev.mutwakil.androidide.omni

import android.content.Context
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Admin-only IDE project file export. The Binder result is metadata, not file bytes.
 * The provider grants exactly one opaque URI to the authenticated first-party recipient.
 */
internal object OmniIdeLargePayload {
    private const val MAX_PAYLOAD_BYTES = 8L * 1024L * 1024L * 1024L
    private const val BUFFER_SIZE = 256 * 1024

    suspend fun export(
        context: Context,
        path: String,
        recipientPackage: String
    ): JsonObject = withContext(Dispatchers.IO) {
        val file = OmniIdeStateBridge.resolveProjectPath(path)
        require(file.isFile && file.canRead()) { "Project payload is not a readable file" }
        val length = file.length()
        require(length in 0..MAX_PAYLOAD_BYTES) { "File exceeds 8 GiB transfer cap" }
        val modified = file.lastModified()
        val sha256 = sha256(file)
        require(file.length() == length && file.lastModified() == modified) {
            "Source changed during checksum; retry after build finishes"
        }

        val uri = OmniIdePayloadProvider.grantRead(context, file, recipientPackage)
        buildJsonObject {
            put("payloadId", UUID.randomUUID().toString())
            put("transport", "CONTENT_URI")
            put("lengthBytes", length)
            put(
                "mimeType",
                URLConnection.guessContentTypeFromName(file.name)
                    ?: "application/octet-stream"
            )
            put("sha256", sha256)
            put("uri", uri.toString())
            put("fileName", file.name)
            put("expiresInSeconds", 900)
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
