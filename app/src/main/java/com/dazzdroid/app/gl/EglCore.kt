package com.dazzdroid.app.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** Minimal EGL14 helper (one context per thread that uses it). */
class EglCore(recordable: Boolean = true) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val context: EGLContext
    private val config: EGLConfig

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        config = chooseConfig(recordable, true) ?: chooseConfig(recordable, false)
            ?: chooseConfig(false, false) ?: error("No suitable EGL config")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    private fun chooseConfig(recordable: Boolean, pbuffer: Boolean): EGLConfig? {
        val attrs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, if (pbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT,
        )
        if (recordable) { attrs += EGL_RECORDABLE_ANDROID; attrs += 1 }
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, num, 0)
        return if (ok && num[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        return s
    }

    /** A tiny pbuffer used to make the context current when no window is attached. */
    fun createOffscreenSurface(): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        return if (s == null || s == EGL14.EGL_NO_SURFACE) EGL14.EGL_NO_SURFACE else s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()) }
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun releaseSurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // eglTerminate is intentionally not called: other threads share the default display.
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
