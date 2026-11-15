package com.dazzdroid.app.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.dazzdroid.app.model.StampStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Classic camera date imprints: orange 7-segment LED, digital, camcorder VCR and DV styles. */
object DateStamp {

    fun draw(canvas: Canvas, w: Int, h: Int, style: StampStyle, date: Date) {
        val short = minOf(w, h).toFloat()
        when (style) {
            StampStyle.OFF -> Unit
            StampStyle.LED -> {
                val text = SimpleDateFormat("''yy MM dd", Locale.US).format(date)
                sevenSeg(canvas, text, w - short * 0.06f, h - short * 0.06f, short * 0.04f, Color.rgb(255, 140, 40))
            }
            StampStyle.DIGITAL -> {
                val text = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(date)
                sevenSeg(canvas, text, w - short * 0.06f, h - short * 0.06f, short * 0.034f, Color.rgb(255, 196, 64))
            }
            StampStyle.VCR -> {
                val p = vcrPaint(short * 0.05f)
                canvas.drawTextShadowed("PLAY ▶", short * 0.07f, short * 0.11f, p)
                val t1 = SimpleDateFormat("a hh:mm", Locale.US).format(date).uppercase(Locale.US)
                val t2 = SimpleDateFormat("MMM. dd yyyy", Locale.US).format(date).uppercase(Locale.US)
                canvas.drawTextShadowed(t1, short * 0.07f, h - short * 0.15f, p)
                canvas.drawTextShadowed(t2, short * 0.07f, h - short * 0.08f, p)
            }
            StampStyle.DV -> {
                val p = vcrPaint(short * 0.04f).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                val t1 = SimpleDateFormat("HH:mm:ss", Locale.US).format(date)
                val t2 = SimpleDateFormat("MMM.dd.yyyy", Locale.US).format(date).uppercase(Locale.US)
                p.textAlign = Paint.Align.RIGHT
                canvas.drawTextShadowed(t2, w - short * 0.06f, h - short * 0.12f, p)
                canvas.drawTextShadowed(t1, w - short * 0.06f, h - short * 0.06f, p)
                val rec = vcrPaint(short * 0.04f).apply { color = Color.WHITE }
                canvas.drawTextShadowed("● REC", short * 0.06f, short * 0.1f, rec)
            }
        }
    }

    /** A transparent overlay bitmap (for the GL preview / video) at the given size. */
    fun overlay(w: Int, h: Int, style: StampStyle, date: Date): Bitmap? {
        if (style == StampStyle.OFF) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w, h, style, date)
        return bmp
    }

    private fun vcrPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.05f
    }

    private fun Canvas.drawTextShadowed(t: String, x: Float, y: Float, p: Paint) {
        val c = p.color
        p.color = Color.argb(160, 0, 0, 0)
        drawText(t, x + p.textSize * 0.06f, y + p.textSize * 0.06f, p)
        p.color = c
        drawText(t, x, y, p)
    }

    // Segment bit order: a b c d e f g
    private val DIGITS = mapOf(
        '0' to 0b1111110, '1' to 0b0110000, '2' to 0b1101101, '3' to 0b1111001, '4' to 0b0110011,
        '5' to 0b1011011, '6' to 0b1011111, '7' to 0b1110000, '8' to 0b1111111, '9' to 0b1111011,
        '-' to 0b0000001,
    )

    /** Draws right-aligned italic 7-segment text with an LED glow; (right, baseline) anchor. */
    private fun sevenSeg(canvas: Canvas, text: String, right: Float, baseline: Float, height: Float, color: Int) {
        val dw = height * 0.55f
        val gap = height * 0.22f
        val thick = height * 0.11f
        val widths = text.map { ch -> when (ch) { ' ' -> dw * 0.6f; '.', '\'' -> dw * 0.35f; else -> dw } + gap }
        var x = right - widths.sum()
        val path = Path()
        val slant = 0.12f
        for ((i, ch) in text.withIndex()) {
            val top = baseline - height
            fun seg(x0: Float, y0: Float, x1: Float, y1: Float) {
                // skew for the classic italic LED look
                val sx0 = x0 + (baseline - y0) * slant
                val sx1 = x1 + (baseline - y1) * slant
                val horiz = y0 == y1
                if (horiz) {
                    path.moveTo(sx0 + thick * 0.5f, y0)
                    path.lineTo(sx0 + thick, y0 - thick / 2); path.lineTo(sx1 - thick, y1 - thick / 2)
                    path.lineTo(sx1 - thick * 0.5f, y1)
                    path.lineTo(sx1 - thick, y1 + thick / 2); path.lineTo(sx0 + thick, y0 + thick / 2)
                    path.close()
                } else {
                    path.moveTo(sx0, y0 + thick * 0.5f)
                    path.lineTo(sx0 + thick / 2, y0 + thick); path.lineTo(sx1 + thick / 2, y1 - thick)
                    path.lineTo(sx1, y1 - thick * 0.5f)
                    path.lineTo(sx1 - thick / 2, y1 - thick); path.lineTo(sx0 - thick / 2, y0 + thick)
                    path.close()
                }
            }
            val mid = top + height / 2
            when (ch) {
                '\'' -> seg(x + dw * 0.15f, top, x + dw * 0.1f, top + height * 0.3f)
                '.' -> path.addCircle(x + dw * 0.1f, baseline - thick / 2, thick * 0.6f, Path.Direction.CW)
                else -> DIGITS[ch]?.let { m ->
                    if (m and 0b1000000 != 0) seg(x, top, x + dw, top)
                    if (m and 0b0100000 != 0) seg(x + dw, top, x + dw, mid)
                    if (m and 0b0010000 != 0) seg(x + dw, mid, x + dw, baseline)
                    if (m and 0b0001000 != 0) seg(x, baseline, x + dw, baseline)
                    if (m and 0b0000100 != 0) seg(x, mid, x, baseline)
                    if (m and 0b0000010 != 0) seg(x, top, x, mid)
                    if (m and 0b0000001 != 0) seg(x, mid, x + dw, mid)
                }
            }
            x += widths[i]
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 150
            maskFilter = BlurMaskFilter(height * 0.25f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, glow)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawPath(path, core)
        val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(110, 255, 240, 200) }
        canvas.drawPath(path, hot)
    }
}
