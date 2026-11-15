package com.dazzdroid.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dazzdroid.app.model.CameraModel
import com.dazzdroid.app.model.FrameStyle
import com.dazzdroid.app.model.StampStyle
import java.io.ByteArrayInputStream
import java.util.Date
import kotlin.math.roundToInt

object PhotoPipeline {

    fun decodeBytes(bytes: ByteArray, maxEdge: Int, mirror: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("Could not decode photo")
        val exif = try { ExifInterface(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }
        return orientAndScale(raw, exif, maxEdge, mirror)
    }

    fun decodeUri(ctx: Context, uri: Uri, maxEdge: Int): Bitmap {
        val r = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val raw = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Could not open image")
        val exif = try { r.openInputStream(uri)?.use { ExifInterface(it) } } catch (e: Exception) { null }
        return orientAndScale(raw, exif, maxEdge, false)
    }

    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        val edge = maxOf(w, h)
        while (edge / (s * 2) >= maxEdge) s *= 2
        return s
    }

    private fun orientAndScale(raw: Bitmap, exif: ExifInterface?, maxEdge: Int, mirror: Boolean): Bitmap {
        val m = Matrix()
        val rot = exif?.rotationDegrees ?: 0
        val flipped = exif?.isFlipped ?: false
        if (flipped) m.postScale(-1f, 1f)
        if (rot != 0) m.postRotate(rot.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        val edge = maxOf(raw.width, raw.height)
        if (edge > maxEdge) {
            val s = maxEdge.toFloat() / edge
            m.postScale(s, s)
        }
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    /** Date imprint + print border. */
    fun finish(img: Bitmap, camera: CameraModel, stamp: StampStyle, date: Date, borders: Boolean, frameNo: Int): Bitmap {
        val stamped = if (stamp != StampStyle.OFF) {
            val m = if (img.isMutable) img else img.copy(Bitmap.Config.ARGB_8888, true)
            DateStamp.draw(Canvas(m), m.width, m.height, stamp, date)
            m
        } else img
        val style = if (borders) camera.frame else FrameStyle.NONE
        return Darkroom.frame(stamped, style, camera.name.uppercase(), frameNo)
    }

    fun thumbnail(b: Bitmap, edge: Int = 256): Bitmap {
        val s = edge.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }
}
