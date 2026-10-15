package com.valeriinov.flutter_gallery

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * The plugin's own ordered list of image URIs, newest first: picked URIs with a persisted
 * read grant, rows the plugin inserted into MediaStore and files it kept in its own folder.
 */
internal class ImageStore(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keptDir = File(context.filesDir, DIR_NAME)
    private val uris: MutableList<String> by lazy { readSavedUris() }
    private val memoryOnlyUris = mutableSetOf<String>()

    @Synchronized
    fun addPicked(picked: List<Uri>): List<String> {
        val listed = picked.map { uri -> listPicked(uri) }
        listed.asReversed().forEach { uri -> prepend(uri) }
        writeUris()
        trimKept()

        return listed
    }

    fun load(offset: Int, limit: Int): List<String> {
        val snapshot = synchronized(this) { uris.toList() }
        val opening = mutableListOf<String>()
        val gone = mutableListOf<String>()
        for (uri in snapshot) {
            if (opening.size == offset + limit) {
                break
            }
            when (probe(uri)) {
                Probe.OPENS -> opening.add(uri)
                Probe.GONE -> gone.add(uri)
                Probe.UNAVAILABLE -> Unit
            }
        }
        if (gone.isNotEmpty()) {
            remove(gone)
        }

        return opening.drop(offset)
    }

    fun has(uri: String): Boolean {
        val isListed = synchronized(this) { uri in uris }

        return isListed && probe(uri) == Probe.OPENS
    }

    fun save(path: String, fileName: String): String {
        val source = File(path)
        val mimeType = mimeTypeOf(fileName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Below Android 10 an app reads no MediaStore row back without READ_EXTERNAL_STORAGE.
            insertLegacy(source, fileName, mimeType)
            return keep(path)
        }
        val uri = insertPending(source, fileName, mimeType)
        addOwn(uri.toString())

        return uri.toString()
    }

    fun keep(path: String): String {
        val source = File(path)
        val uri = source.inputStream().use { input -> copyToKept(input, source.extension) }
        addOwn(uri)
        trimKept()

        return uri
    }

    private fun readSavedUris(): MutableList<String> {
        val saved = JSONArray(prefs.getString(KEY_URIS, null) ?: "[]")

        return MutableList(saved.length()) { index -> saved.getString(index) }
    }

    /**
     * The Google Play services picker grants no persistable access, so the
     * plugin keeps a copy of what it picked there.
     */
    private fun listPicked(uri: Uri): String {
        if (tryPersistGrant(uri)) {
            return uri.toString()
        }
        val copy = tryCopyToKept(uri)
        if (copy != null) {
            return copy
        }
        memoryOnlyUris.add(uri.toString())

        return uri.toString()
    }

    private fun tryPersistGrant(uri: Uri): Boolean =
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            memoryOnlyUris.remove(uri.toString())
            true
        } catch (e: SecurityException) {
            false
        }

    private fun tryCopyToKept(uri: Uri): String? =
        try {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
            resolver.openInputStream(uri)?.use { input -> copyToKept(input, extension.orEmpty()) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }

    private fun copyToKept(input: InputStream, extension: String): String {
        keptDir.mkdirs()
        val target = File(keptDir, "${UUID.randomUUID()}.${extension.ifEmpty { DEFAULT_EXTENSION }}")
        target.outputStream().use { output -> input.copyTo(output) }

        return Uri.fromFile(target).toString()
    }

    private fun prepend(uri: String) {
        uris.remove(uri)
        uris.add(0, uri)
    }

    private fun writeUris() {
        val persisted = uris.filter { it !in memoryOnlyUris }
        prefs.edit().putString(KEY_URIS, JSONArray(persisted).toString()).apply()
    }

    private fun probe(uri: String): Probe =
        try {
            val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "r")
            descriptor?.close()
            if (descriptor == null) Probe.UNAVAILABLE else Probe.OPENS
        } catch (e: SecurityException) {
            Probe.GONE
        } catch (e: FileNotFoundException) {
            Probe.GONE
        } catch (e: Exception) {
            Probe.UNAVAILABLE
        }

    @Synchronized
    private fun remove(gone: List<String>) {
        removeLocked(gone)
    }

    private fun removeLocked(gone: List<String>) {
        gone.forEach { uri ->
            uris.remove(uri)
            memoryOnlyUris.remove(uri)
            releaseGrant(Uri.parse(uri))
        }
        writeUris()
    }

    private fun releaseGrant(uri: Uri) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(uri.path.orEmpty()).delete()
            return
        }
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // A MediaStore row the plugin inserted holds no persisted grant.
        }
    }

    private fun insertPending(source: File, fileName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            writeTo(uri, source)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return uri
    }

    @Suppress("DEPRECATION")
    private fun insertLegacy(source: File, fileName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            writeTo(uri, source)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return uri
    }

    private fun writeTo(uri: Uri, source: File) {
        val output = resolver.openOutputStream(uri) ?: throw IOException("MediaStore row does not open")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    @Synchronized
    private fun addOwn(uri: String) {
        prepend(uri)
        writeUris()
    }

    @Synchronized
    private fun trimKept() {
        val excess = uris.filter { Uri.parse(it).scheme == ContentResolver.SCHEME_FILE }.drop(KEPT_LIMIT)
        if (excess.isNotEmpty()) {
            removeLocked(excess)
        }
    }

    private fun mimeTypeOf(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(fileName).extension.lowercase())
            ?: DEFAULT_MIME_TYPE

    private enum class Probe { OPENS, GONE, UNAVAILABLE }

    companion object {
        const val SAVE_FOLDER = "GoGoBag"

        /** The most images [keep] holds; the oldest copies past it are deleted. */
        const val KEPT_LIMIT = 100

        private const val PREFS_NAME = "flutter_gallery"
        private const val KEY_URIS = "uris"
        private const val DIR_NAME = "flutter_gallery"
        private const val DEFAULT_EXTENSION = "jpg"
        private const val DEFAULT_MIME_TYPE = "image/jpeg"
    }
}
