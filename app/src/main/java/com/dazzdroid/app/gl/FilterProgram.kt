package com.dazzdroid.app.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.dazzdroid.app.model.Lens
import com.dazzdroid.app.model.Look
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The film engine shader, compiled for either an external OES source or a 2D bitmap source. */
class FilterProgram(context: Context, private val oes: Boolean) {
    private val program: Int
    private val locs = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val dummyTex: Int
    private val aPos: Int
    private val mat3 = FloatArray(9)

    init {
        val vs = load(context, "shaders/filter.vert")
        val header = if (oes) "#extension GL_OES_EGL_image_external : require\n#define SRC_OES\n" else ""
        val fs = header + load(context, "shaders/filter.frag")
        program = GlUtil.program(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(byteArrayOf(0, 0, 0, 0)).position(0)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        dummyTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
    }

    private fun u(name: String): Int = locs.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    fun draw(
        srcTex: Int,
        texMatrix: FloatArray,
        orient: FloatArray,
        crop: FloatArray,
        outW: Int,
        outH: Int,
        flipY: Boolean,
        time: Float,
        look: Look,
        overlayTex: Int = 0,
        doubleTex: Int = 0,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(u("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (overlayTex != 0) overlayTex else dummyTex)
        GLES20.glUniform1i(u("uOverlay"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (doubleTex != 0) doubleTex else dummyTex)
        GLES20.glUniform1i(u("uDouble"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix2fv(u("uOrient"), 1, false, orient, 0)
        GLES20.glUniform4f(u("uCrop"), crop[0], crop[1], crop[2], crop[3])
        GLES20.glUniform2f(u("uRes"), outW.toFloat(), outH.toFloat())
        GLES20.glUniform1f(u("uTime"), time)
        GLES20.glUniform1f(u("uFlipY"), if (flipY) -1f else 1f)
        GLES20.glUniform1f(u("uIntensity"), look.intensity)
        GLES20.glUniform1f(u("uOverlayOn"), if (overlayTex != 0) 1f else 0f)
        GLES20.glUniform1f(u("uDoubleOn"), if (doubleTex != 0) 1f else 0f)
        GLES20.glUniform1i(u("uLens"), look.lens.shaderId)
        GLES20.glUniform1f(u("uLensAmt"), look.lensAmount)

        for ((key, v) in look.spec.values) {
            val loc = u("u" + key.replaceFirstChar { it.uppercaseChar() })
            if (loc < 0) continue
            when (v.size) {
                1 -> GLES20.glUniform1f(loc, v[0])
                3 -> GLES20.glUniform3f(loc, v[0], v[1], v[2])
                9 -> {
                    // row-major (JSON) -> column-major (GL)
                    for (r in 0..2) for (c in 0..2) mat3[c * 3 + r] = v[r * 3 + c]
                    GLES20.glUniformMatrix3fv(loc, 1, false, mat3, 0)
                }
            }
        }

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GlUtil.deleteTexture(dummyTex)
    }

    companion object {
        private val cache = HashMap<String, String>()
        fun load(context: Context, path: String): String = synchronized(cache) {
            cache.getOrPut(path) { context.assets.open(path).bufferedReader().use { it.readText() } }
        }
    }
}

/** Folds user adjustments, flash and lens attachments into the camera's base spec. */
object LookBuilder {
    fun build(
        base: com.dazzdroid.app.model.FilterSpec,
        adj: com.dazzdroid.app.model.Adjustments,
        lens: Lens,
        lensAmount: Float,
        flash: Boolean,
        shaderEv: Float,
        leakSeed: Float,
    ): Look {
        val spec = base.edit {
            this["exposure"] = floatArrayOf(this.getValue("exposure")[0] + shaderEv +
                (if (lens == Lens.ND) -(0.8f + 1.6f * lensAmount) else 0f))
            this["temp"] = floatArrayOf(this.getValue("temp")[0] + (adj.kelvin - 5500) / 4500f * 0.8f)
            this["tint"] = floatArrayOf(this.getValue("tint")[0] + adj.tint * 0.6f)
            if (flash) this["flash"] = floatArrayOf(this.getValue("flash")[0] + 0.45f)
            this["leakSeed"] = floatArrayOf(leakSeed)
        }
        return Look(spec, adj.intensity, lens, lensAmount)
    }
}
