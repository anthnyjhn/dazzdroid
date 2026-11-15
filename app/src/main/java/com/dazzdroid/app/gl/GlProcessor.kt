package com.dazzdroid.app.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import com.dazzdroid.app.model.Look
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Runs the film engine on bitmaps (stills, imports, multi-frame composites) on a private GL thread. */
class GlProcessor private constructor(private val context: Context) {
    private val thread = HandlerThread("dazz-proc").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var surface: EGLSurface
    private lateinit var program: FilterProgram
    var maxTex = 4096
        private set

    init {
        handler.post {
            egl = EglCore(recordable = false)
            surface = egl.createOffscreenSurface()
            egl.makeCurrent(surface)
            program = FilterProgram(context, oes = false)
            maxTex = GlUtil.maxTextureSize()
        }
    }

    /**
     * Applies [look] to an upright bitmap, center-cropping to [aspect] (w/h; null keeps the source
     * aspect). [double] is screen-blended on top for double exposures (it is stretched to fit).
     */
    suspend fun process(
        src: Bitmap,
        look: Look,
        aspect: Float?,
        time: Float,
        double: Bitmap? = null,
        maxEdge: Int = Int.MAX_VALUE,
    ): Bitmap = suspendCancellableCoroutine { cont ->
        handler.post {
            try {
                cont.resume(run(src, look, aspect, time, double, maxEdge))
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    private fun run(src0: Bitmap, look: Look, aspect: Float?, time: Float, double: Bitmap?, maxEdge: Int): Bitmap {
        val limit = minOf(maxTex, maxEdge)
        var src = src0
        if (src.width > maxTex || src.height > maxTex) {
            val s = maxTex.toFloat() / maxOf(src.width, src.height)
            src = Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
        }
        val a = aspect ?: (src.width.toFloat() / src.height)
        var (w, h) = Geometry.cropSize(src.width, src.height, a)
        if (maxOf(w, h) > limit) {
            val s = limit.toFloat() / maxOf(w, h)
            w = (w * s).roundToInt(); h = (h * s).roundToInt()
        }
        egl.makeCurrent(surface)
        val tex = GlUtil.bitmapTexture(src)
        val dTex = double?.let { GlUtil.bitmapTexture(it) } ?: 0
        val fbo = GlUtil.Fbo(w, h)
        try {
            fbo.bind()
            program.draw(
                tex, Geometry.FLIP_V, Geometry.orient(0, false), Geometry.crop(src.width, src.height, w, h),
                w, h, flipY = true, time = time, look = look, doubleTex = dTex,
            )
            GLES20.glFinish()
            return fbo.read()
        } finally {
            fbo.release()
            GlUtil.deleteTexture(tex)
            GlUtil.deleteTexture(dTex)
            if (src !== src0) src.recycle()
        }
    }

    companion object {
        @Volatile private var instance: GlProcessor? = null
        fun get(context: Context): GlProcessor =
            instance ?: synchronized(this) { instance ?: GlProcessor(context.applicationContext).also { instance = it } }
    }
}
