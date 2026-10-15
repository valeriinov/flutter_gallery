package com.valeriinov.flutter_gallery

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Reads thumbnails and original copies of image URIs. */
internal class ImageReader(context: Context) {
    private val resolver = context.contentResolver
    private val cacheDir = File(context.cacheDir, DIR_NAME)

    fun readThumbnail(uri: String, size: Int): ByteArray {
        val parsed = Uri.parse(uri)
        val bitmap = if (canLoadSystemThumbnail(parsed)) {
            tryLoadSystemThumbnail(parsed, size) ?: decodeThumbnail(parsed, size)
        } else {
            decodeThumbnail(parsed, size)
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()

        return output.toByteArray()
    }

    fun readOriginal(uri: String): String {
        val parsed = Uri.parse(uri)
        cacheDir.mkdirs()
        val target = File(cacheDir, "${UUID.randomUUID()}.${extensionOf(parsed)}")
        open(parsed).use { input -> target.outputStream().use { input.copyTo(it) } }

        return target.absolutePath
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    /** `loadThumbnail` reads through a content provider only and exists from API 29. */
    private fun canLoadSystemThumbnail(uri: Uri): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT

    /**
     * `loadThumbnail` fits the image inside the requested size rather than
     * covering it, and on Android 10 MediaProvider builds none for a
     * `media/external/file` URI.
     */
    private fun tryLoadSystemThumbnail(uri: Uri, size: Int): Bitmap? {
        val requested = size * SYSTEM_THUMBNAIL_OVERSCAN
        val bitmap = try {
            resolver.loadThumbnail(uri, Size(requested, requested), null)
        } catch (e: IOException) {
            return null
        }

        return transform(bitmap, size, Matrix())
    }

    private fun decodeThumbnail(uri: Uri, size: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size)
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("The image does not decode")
        val exif = open(uri).use { ExifInterface(it) }
        val orientation = Matrix().apply {
            postRotate(exif.rotationDegrees.toFloat())
            if (exif.isFlipped) {
                postScale(-1f, 1f)
            }
        }

        return transform(decoded, size, orientation)
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("The image does not open")

    private fun sampleSize(width: Int, height: Int, size: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= size && height / (sample * 2) >= size) {
            sample *= 2
        }

        return sample
    }

    private fun transform(bitmap: Bitmap, size: Int, orientation: Matrix): Bitmap {
        val scale = minOf(1f, size.toFloat() / minOf(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postConcat(orientation)
        }
        if (matrix.isIdentity) {
            return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed != bitmap) {
            bitmap.recycle()
        }

        return transformed
    }

    private fun extensionOf(uri: Uri): String {
        val extension = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(uri.path.orEmpty()).extension
        } else {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
        }

        return extension?.ifEmpty { null } ?: DEFAULT_EXTENSION
    }

    companion object {
        private const val DIR_NAME = "flutter_gallery"
        private const val DEFAULT_EXTENSION = "jpg"
        private const val JPEG_QUALITY = 90
        private const val SYSTEM_THUMBNAIL_OVERSCAN = 2
    }
}
