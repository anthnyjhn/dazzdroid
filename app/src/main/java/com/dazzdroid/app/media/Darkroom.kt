package com.dazzdroid.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.dazzdroid.app.model.FrameStyle
import kotlin.math.roundToInt

/** Print finishing: film rebates, instant-film cards and multi-frame layouts. */
object Darkroom {

    fun frame(img: Bitmap, style: FrameStyle, label: String, frameNo: Int): Bitmap = when (style) {
        FrameStyle.NONE -> img
        FrameStyle.INSTAX_MINI -> card(img, 4f / 46f, 4f / 46f, 6f / 46f, 18f / 46f, Color.rgb(248, 246, 241))
        FrameStyle.INSTAX_SQUARE -> card(img, 5f / 62f, 5f / 62f, 6.5f / 62f, 17.5f / 62f, Color.rgb(248, 246, 241))
        FrameStyle.POLAROID -> card(img, 4.5f / 79f, 4.5f / 79f, 6f / 79f, 22f / 79f, Color.rgb(244, 241, 234))
        FrameStyle.FILM35 -> film35(img, label, frameNo)
        FrameStyle.FILM120 -> film120(img, label, frameNo)
    }

    private fun card(img: Bitmap, l: Float, r: Float, t: Float, b: Float, color: Int): Bitmap {
        val w = img.width
        val ml = (w * l).roundToInt(); val mr = (w * r).roundToInt()
        val mt = (w * t).roundToInt(); val mb = (w * b).roundToInt()
        val out = Bitmap.createBitmap(w + ml + mr, img.height + mt + mb, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(color)
        // faint paper texture shading toward the bottom
        val shade = Paint().apply { this.color = Color.argb(10, 120, 100, 80) }
        c.drawRect(0f, out.height * 0.6f, out.width.toFloat(), out.height.toFloat(), shade)
        c.drawBitmap(img, ml.toFloat(), mt.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val edge = Paint().apply { this.color = Color.argb(40, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = maxOf(1f, w / 600f) }
        c.drawRect(ml.toFloat(), mt.toFloat(), (ml + img.width).toFloat(), (mt + img.height).toFloat(), edge)
        return out
    }

    private fun edgePaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 134, 42)
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** 35mm negative strip: sprockets along the long edges and orange edge print. */
    private fun film35(img: Bitmap, label: String, frameNo: Int): Bitmap {
        val landscape = img.width >= img.height
        val src = if (landscape) img else rotate(img, 90f)
        val w = src.width; val h = src.height
        val rebate = (h * 5.5f / 24f).roundToInt()
        val side = (h * 0.06f).roundToInt()
        val out = Bitmap.createBitmap(w + side * 2, h + rebate * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(18, 14, 12))
        c.drawBitmap(src, side.toFloat(), rebate.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 230, 220) }
        val hw = h * 0.072f; val hh = h * 0.1f
        val pitch = h * 4.75f / 24f
        var x = side * 0.5f
        while (x + hw < out.width) {
            val r = hw * 0.18f
            c.drawRoundRect(RectF(x, rebate * 0.22f, x + hw, rebate * 0.22f + hh), r, r, hole)
            c.drawRoundRect(RectF(x, out.height - rebate * 0.22f - hh, x + hw, out.height - rebate * 0.22f), r, r, hole)
            x += pitch
        }
        val p = edgePaint(rebate * 0.24f)
        c.drawText("$label 400", side + w * 0.08f, out.height - rebate * 0.12f - hh - rebate * 0.08f, p)
        c.drawText("▶ $frameNo", side + w * 0.62f, out.height - rebate * 0.12f - hh - rebate * 0.08f, p)
        c.drawText("${frameNo}A", side + w * 0.82f, rebate * 0.94f, p)
        if (src !== img) src.recycle()
        return if (landscape) out else rotate(out, -90f).also { out.recycle() }
    }

    /** 120 medium-format rebate: thick black border with a notch and edge print. */
    private fun film120(img: Bitmap, label: String, frameNo: Int): Bitmap {
        val short = minOf(img.width, img.height)
        val b = (short * 0.07f).roundToInt()
        val out = Bitmap.createBitmap(img.width + 2 * b, img.height + 2 * b, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(14, 12, 11))
        c.drawBitmap(img, b.toFloat(), b.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val notch = Paint().apply { color = Color.rgb(14, 12, 11) }
        val nw = b * 0.5f; val nh = b * 0.9f
        c.drawRect(b.toFloat(), b + img.height * 0.3f, b + nw, b + img.height * 0.3f + nh, notch)
        c.drawRect(b.toFloat(), b + img.height * 0.36f, b + nw * 0.6f, b + img.height * 0.36f + nh * 0.5f, notch)
        val p = edgePaint(b * 0.42f)
        c.drawText("$label  $frameNo", b * 1.2f, b * 0.68f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("▶▶ 120", out.width - b * 1.2f, out.height - b * 0.32f, p)
        return out
    }

    fun rotate(src: Bitmap, deg: Float): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Two vertical half-frames side by side on one 35mm frame. */
    fun halfFrame(a: Bitmap, b: Bitmap): Bitmap {
        val h = minOf(a.height, b.height)
        val gap = (h * 0.025f).roundToInt()
        val wa = (a.width * h.toFloat() / a.height).roundToInt()
        val wb = (b.width * h.toFloat() / b.height).roundToInt()
        val out = Bitmap.createBitmap(wa + wb + gap, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(10, 10, 10))
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(a, null, Rect(0, 0, wa, h), p)
        c.drawBitmap(b, null, Rect(wa + gap, 0, wa + gap + wb, h), p)
        return out
    }

    /** Four sequential lens frames in a 2x2 grid. */
    fun grid4(frames: List<Bitmap>): Bitmap {
        val fw = frames[0].width; val fh = frames[0].height
        val gap = (minOf(fw, fh) * 0.02f).roundToInt()
        val out = Bitmap.createBitmap(fw * 2 + gap, fh * 2 + gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(10, 10, 10))
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.take(4).forEachIndexed { i, f ->
            val x = (i % 2) * (fw + gap); val y = (i / 2) * (fh + gap)
            c.drawBitmap(f, null, Rect(x, y, x + fw, y + fh), p)
        }
        return out
    }
}
