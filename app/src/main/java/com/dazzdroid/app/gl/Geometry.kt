package com.dazzdroid.app.gl

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object Geometry {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** Maps upright uv to a Bitmap texture (row 0 = top). */
    val FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    /**
     * Column-major mat2 taking centered upright coordinates to centered buffer coordinates, for a
     * buffer that must be rotated [degrees] clockwise to be upright, optionally mirrored horizontally.
     */
    fun orient(degrees: Int, mirror: Boolean): FloatArray {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).roundToInt().toFloat()
        val s = sin(r).roundToInt().toFloat()
        return if (mirror) floatArrayOf(-c, -s, -s, c) else floatArrayOf(c, s, -s, c)
    }

    /** Center-crop (scaleX, scaleY, offX, offY) of a srcW x srcH image to the dst aspect, with optional zoom. */
    fun crop(srcW: Int, srcH: Int, dstW: Int, dstH: Int, zoom: Float = 1f): FloatArray {
        val srcA = srcW.toFloat() / srcH
        val dstA = dstW.toFloat() / dstH
        var sx = 1f
        var sy = 1f
        if (srcA > dstA) sx = dstA / srcA else sy = srcA / dstA
        sx /= zoom
        sy /= zoom
        return floatArrayOf(sx, sy, (1f - sx) / 2f, (1f - sy) / 2f)
    }

    /** Output size of center-cropping srcW x srcH to the aspect dstA (w/h). */
    fun cropSize(srcW: Int, srcH: Int, dstA: Float): Pair<Int, Int> {
        val srcA = srcW.toFloat() / srcH
        return if (srcA > dstA) Pair((srcH * dstA).roundToInt(), srcH) else Pair(srcW, (srcW / dstA).roundToInt())
    }
}
