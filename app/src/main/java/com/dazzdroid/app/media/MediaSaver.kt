package com.dazzdroid.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes results into the shared gallery (Pictures/DazzDroid and Movies/DazzDroid). */
object MediaSaver {
    private const val FOLDER = "DazzDroid"

    fun name(prefix: String) = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun saveJpeg(ctx: Context, bmp: Bitmap, prefix: String = "DAZZ", quality: Int = 95): Uri? =
        write(ctx, name(prefix) + ".jpg", "image/jpeg", false) { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }

    fun saveBytes(ctx: Context, bytes: ByteArray, fileName: String, mime: String): Uri? =
        write(ctx, fileName, mime, false) { it.write(bytes) }

    fun saveVideoFile(ctx: Context, file: File, prefix: String = "DAZZ"): Uri? =
        write(ctx, name(prefix) + ".mp4", "video/mp4", true) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun write(ctx: Context, fileName: String, mime: String, video: Boolean, body: (OutputStream) -> Unit): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(collection, values) ?: return null
            try {
                r.openOutputStream(uri)?.use(body)
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                r.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                r.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            val f = File(dir, fileName)
            try {
                FileOutputStream(f).use(body)
            } catch (e: Exception) {
                return null
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, f.absolutePath)
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = try { ctx.contentResolver.insert(collection, values) } catch (e: Exception) { null }
            if (uri == null) MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
            uri
        }
    }
}
