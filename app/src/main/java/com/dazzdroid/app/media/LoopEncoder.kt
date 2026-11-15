package com.dazzdroid.app.media

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import com.dazzdroid.app.gl.EglCore
import com.dazzdroid.app.gl.FilterProgram
import com.dazzdroid.app.gl.Geometry
import com.dazzdroid.app.gl.GlUtil
import com.dazzdroid.app.model.FilterSpec
import com.dazzdroid.app.model.Look
import java.io.File

/** Encodes already-processed frames as a looping "wiggle" MP4 (ping-pong order). */
object LoopEncoder {
    fun encode(ctx: Context, frames: List<Bitmap>, out: File, frameMs: Int = 120, seconds: Int = 4) {
        val w0 = frames[0].width
        val h0 = frames[0].height
        val w = (w0 / 16) * 16
        val h = (h0 / 16) * 16
        val sink = MuxerSink(out.absolutePath, 1)
        val egl = EglCore(recordable = true)
        val enc = SurfaceEncoder(w, h, SurfaceEncoder.bitrateFor(w, h), 1000 / frameMs, sink)
        val surface = egl.createWindowSurface(enc.inputSurface)
        egl.makeCurrent(surface)
        val program = FilterProgram(ctx, oes = false)
        val textures = frames.map { GlUtil.bitmapTexture(it) }
        val order = frames.indices.toList() + frames.indices.reversed().drop(1).dropLast(1)
        val look = Look(FilterSpec.NEUTRAL)
        val crop = Geometry.crop(w0, h0, w, h)
        try {
            val total = seconds * 1000 / frameMs
            for (i in 0 until total) {
                val tex = textures[order[i % order.size]]
                GLES20.glViewport(0, 0, w, h)
                program.draw(tex, Geometry.FLIP_V, Geometry.orient(0, false), crop, w, h, false, 0f, look)
                egl.setPresentationTime(surface, i * frameMs * 1_000_000L)
                egl.swap(surface)
                enc.drain(false)
            }
            enc.signalEnd()
            enc.drain(true)
        } finally {
            textures.forEach { GlUtil.deleteTexture(it) }
            program.release()
            enc.release()
            sink.stop()
            egl.releaseSurface(surface)
            egl.release()
        }
    }
}
