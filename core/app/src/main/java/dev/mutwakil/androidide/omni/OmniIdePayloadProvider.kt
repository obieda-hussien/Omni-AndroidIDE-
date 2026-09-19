package dev.mutwakil.androidide.omni

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Grant-scoped, read-only file descriptors for verified first-party Omni peers.
 *
 * The URI contains a random opaque ID, not a filesystem path. No payload bytes go through Binder,
 * and the provider never allows callers to select arbitrary files via a URI path.
 */
class OmniIdePayloadProvider : ContentProvider() {
    private data class Grant(
        val file: File,
        val recipientPackage: String,
        val size: Long,
        val modifiedAt: Long,
        val expiresAt: Long
    )

    companion object {
        private const val MAX_GRANTS = 32
        private const val GRANT_TTL_MS = 15 * 60 * 1000L
        private val grants = ConcurrentHashMap<String, Grant>()

        fun grantRead(context: Context, file: File, recipientPackage: String): Uri {
            val canonical = file.canonicalFile
            require(canonical.isFile) { "Payload source is not a regular file" }
            require(
                context.packageManager.checkSignatures(context.packageName, recipientPackage) ==
                    PackageManager.SIGNATURE_MATCH
            ) { "Payload recipient is not a verified Omni signer" }
            val now = System.currentTimeMillis()
            grants.entries.removeAll { it.value.expiresAt < now }
            require(grants.size < MAX_GRANTS) { "Too many pending payload grants" }
            val token = UUID.randomUUID().toString()
            grants[token] = Grant(
                canonical, recipientPackage, canonical.length(),
                canonical.lastModified(), now + GRANT_TTL_MS
            )
            val uri = Uri.Builder()
                .scheme("content")
                .authority(context.packageName + ".omnilink.payloads")
                .appendPath(token)
                .build()
            context.grantUriPermission(recipientPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only payload")
        val app = requireNotNull(context)
        if (uri.authority != app.packageName + ".omnilink.payloads") {
            throw FileNotFoundException("Unknown payload authority")
        }
        val token = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException("Bad payload URI")
        val grant = grants[token] ?: throw FileNotFoundException("Payload expired")
        if (grant.expiresAt < System.currentTimeMillis()) {
            grants.remove(token)
            throw FileNotFoundException("Payload expired")
        }
        val uid = Binder.getCallingUid()
        val packages = app.packageManager.getPackagesForUid(uid).orEmpty()
        if (uid == Process.myUid() ||
            grant.recipientPackage !in packages ||
            app.packageManager.checkSignatures(uid, Process.myUid()) !=
                PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Caller is not the approved Omni payload recipient")
        }
        if (!grant.file.isFile || grant.file.length() != grant.size ||
            grant.file.lastModified() != grant.modifiedAt
        ) {
            throw FileNotFoundException("Payload source changed after grant")
        }
        return ParcelFileDescriptor.open(grant.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only payload provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only payload provider")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read-only payload provider")
}
