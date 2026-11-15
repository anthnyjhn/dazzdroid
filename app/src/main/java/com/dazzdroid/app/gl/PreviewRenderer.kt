package com.dazzdroid.app.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.dazzdroid.app.media.SurfaceEncoder
import com.dazzdroid.app.model.FilterSpec
import com.dazzdroid.app.model.Look
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the camera SurfaceTexture and renders every camera frame through the film engine to the
 * viewfinder and (while recording) to the video encoder.
 */
class PreviewRenderer(private val context: Context) : SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("dazz-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var egl: EglCore
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var program: FilterProgram
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val t0 = System.nanoTime()

    private var display: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displayW = 0
    private var displayH = 0

    @Volatile var look: Look = Look(FilterSpec.NEUTRAL)
    @Volatile private var orient = Geometry.orient(0, false)
    @Volatile private var uprightW = 3
    @Volatile private var uprightH = 4
    @Volatile var zoom = 1f

    private var overlayTex = 0
    private var doubleTex = 0

    private var encoder: SurfaceEncoder? = null
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val snapshots = ArrayList<SnapshotRequest>()

    private class SnapshotRequest(val w: Int, val h: Int, val cb: (Bitmap) -> Unit)

    fun start(onReady: (SurfaceTexture) -> Unit) {
        handler.post {
            egl = EglCore(recordable = true)
            pbuffer = egl.createOffscreenSurface()
            egl.makeCurrent(pbuffer)
            program = FilterProgram(context, oes = true)
            oesTex = GlUtil.oesTexture()
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener(this, handler)
            surfaceTexture = st
            main.post { onReady(st) }
        }
    }

    /**
     * Camera buffer geometry. Camera2 already sets the buffer transform (sensor rotation and
     * front-camera mirroring) on the SurfaceTexture, so the transform matrix delivers upright
     * frames; we only need the upright size for center-cropping.
     */
    fun setSource(bufW: Int, bufH: Int, sensorRotation: Int) {
        val swap = sensorRotation % 180 != 0
        uprightW = if (swap) bufH else bufW
        uprightH = if (swap) bufW else bufH
        orient = Geometry.orient(0, false)
    }

    fun setDisplay(surface: Surface?, w: Int, h: Int) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (display != EGL14.EGL_NO_SURFACE) {
                    egl.makeCurrent(pbuffer)
                    egl.releaseSurface(display)
                    display = EGL14.EGL_NO_SURFACE
                }
                if (surface != null && surface.isValid) {
                    display = egl.createWindowSurface(surface)
                    displayW = w
                    displayH = h
                }
            } catch (_: Exception) {
                display = EGL14.EGL_NO_SURFACE
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun setOverlay(bmp: Bitmap?) = handler.post {
        egl.makeCurrent(pbuffer)
        GlUtil.deleteTexture(overlayTex)
        overlayTex = bmp?.let { GlUtil.bitmapTexture(it) } ?: 0
    }

    fun setDoubleExposure(bmp: Bitmap?) = handler.post {
        egl.makeCurrent(pbuffer)
        GlUtil.deleteTexture(doubleTex)
        doubleTex = bmp?.let { GlUtil.bitmapTexture(it) } ?: 0
    }

    fun startRecording(enc: SurfaceEncoder) = handler.post {
        encSurface = egl.createWindowSurface(enc.inputSurface)
        encoder = enc
    }

    fun stopRecording(done: () -> Unit) = handler.post {
        val enc = encoder
        encoder = null
        if (enc != null) {
            enc.signalEnd()
            enc.drain(true)
            egl.makeCurrent(pbuffer)
            egl.releaseSurface(encSurface)
            encSurface = EGL14.EGL_NO_SURFACE
            enc.release()
        }
        done()
    }

    /** Renders the next frame (with the current look) at w x h and returns it on the main thread. */
    fun snapshot(w: Int, h: Int, cb: (Bitmap) -> Unit) = handler.post { snapshots += SnapshotRequest(w, h, cb) }

    override fun onFrameAvailable(st: SurfaceTexture) {
        try {
            drawFrame(st)
        } catch (_: Exception) {
            // Surface churn during lifecycle changes; the next frame will recover.
        }
    }

    private fun drawFrame(st: SurfaceTexture) {
        egl.makeCurrent(if (display != EGL14.EGL_NO_SURFACE) display else pbuffer)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val time = (System.nanoTime() - t0) / 1e9f
        val l = look
        val o = orient

        if (display != EGL14.EGL_NO_SURFACE && displayW > 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, displayW, displayH)
            program.draw(oesTex, texMatrix, o, Geometry.crop(uprightW, uprightH, displayW, displayH, zoom),
                displayW, displayH, false, time, l, overlayTex, doubleTex)
            egl.swap(display)
        }

        val enc = encoder
        if (enc != null && encSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent(encSurface)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, enc.width, enc.height)
            program.draw(oesTex, texMatrix, o, Geometry.crop(uprightW, uprightH, enc.width, enc.height, zoom),
                enc.width, enc.height, false, time, l, overlayTex, 0)
            egl.setPresentationTime(encSurface, System.nanoTime())
            egl.swap(encSurface)
            enc.drain(false)
        }

        if (snapshots.isNotEmpty()) {
            val reqs = ArrayList(snapshots)
            snapshots.clear()
            for (r in reqs) {
                val fbo = GlUtil.Fbo(r.w, r.h)
                fbo.bind()
                program.draw(oesTex, texMatrix, o, Geometry.crop(uprightW, uprightH, r.w, r.h, zoom),
                    r.w, r.h, true, time, l, 0, 0)
                val bmp = fbo.read()
                fbo.release()
                main.post { r.cb(bmp) }
            }
        }
    }

    fun release() {
        handler.post {
            try {
                surfaceTexture?.release()
                egl.makeCurrent(pbuffer)
                GlUtil.deleteTexture(oesTex)
                GlUtil.deleteTexture(overlayTex)
                GlUtil.deleteTexture(doubleTex)
                program.release()
                if (display != EGL14.EGL_NO_SURFACE) egl.releaseSurface(display)
                egl.releaseSurface(pbuffer)
                egl.release()
            } catch (_: Exception) {
            }
            thread.quitSafely()
        }
    }
}
