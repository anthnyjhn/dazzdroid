package com.dazzdroid.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.dazzdroid.app.gl.EglCore
import com.dazzdroid.app.gl.FilterProgram
import com.dazzdroid.app.gl.Geometry
import com.dazzdroid.app.gl.GlUtil
import com.dazzdroid.app.model.Look
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Re-renders an imported video through the film engine (blocking; call from a worker). */
object VideoTranscoder {

    fun transcode(
        ctx: Context,
        input: Uri,
        output: File,
        look: Look,
        overlay: Bitmap?,
        maxShort: Int,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, input, null)
        var vTrack = -1
        var aTrack = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && vTrack < 0) vTrack = i
            if (mime.startsWith("audio/") && aTrack < 0) aTrack = i
        }
        require(vTrack >= 0) { "No video track" }
        val vFmt = extractor.getTrackFormat(vTrack)
        val srcW = vFmt.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = vFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) vFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val durationUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else 1L
        val upW = if (rotation % 180 != 0) srcH else srcW
        val upH = if (rotation % 180 != 0) srcW else srcH
        val scale = minOf(1f, maxShort.toFloat() / minOf(upW, upH))
        val outW = ((upW * scale) / 16).roundToInt() * 16
        val outH = ((upH * scale) / 16).roundToInt() * 16

        val aFmt = if (aTrack >= 0) extractor.getTrackFormat(aTrack) else null
        val sink = MuxerSink(output.absolutePath, if (aFmt != null) 2 else 1)
        val audioOut = aFmt?.let {
            try { sink.addTrack(it) } catch (e: Exception) { sink.abandonTrack(); -1 }
        } ?: -1

        val egl = EglCore(recordable = true)
        val encoder = SurfaceEncoder(outW, outH, SurfaceEncoder.bitrateFor(outW, outH), 30, sink)
        val encSurface = egl.createWindowSurface(encoder.inputSurface)
        egl.makeCurrent(encSurface)
        val program = FilterProgram(ctx, oes = true)
        val oes = GlUtil.oesTexture()
        val overlayTex = overlay?.let { GlUtil.bitmapTexture(it) } ?: 0
        val listenerThread = HandlerThread("dazz-st").apply { start() }
        val st = SurfaceTexture(oes)
        val lock = Object()
        var frameReady = false
        st.setOnFrameAvailableListener({ synchronized(lock) { frameReady = true; lock.notifyAll() } }, Handler(listenerThread.looper))
        val decSurface = Surface(st)
        val decoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(vFmt, decSurface, null, 0)
        decoder.start()

        val texMatrix = FloatArray(16)
        val orient = Geometry.orient(rotation, false)
        val crop = floatArrayOf(1f, 1f, 0f, 0f)
        val info = MediaCodec.BufferInfo()
        extractor.selectTrack(vTrack)
        var inputDone = false
        var outputDone = false
        // Original audio is passed through untouched, interleaved with the video as it is rendered.
        val ax = if (aTrack >= 0 && audioOut >= 0) MediaExtractor().apply { setDataSource(ctx, input, null); selectTrack(aTrack) } else null
        val abuf = ByteBuffer.allocate(1 shl 20)
        val ai = MediaCodec.BufferInfo()
        var audioDone = ax == null
        fun pumpAudio(untilUs: Long) {
            val x = ax ?: return
            while (!audioDone) {
                val t = x.sampleTime
                if (t < 0) { audioDone = true; return }
                if (t > untilUs) return
                abuf.clear()
                val n = x.readSampleData(abuf, 0)
                if (n < 0) { audioDone = true; return }
                ai.set(0, n, t, if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                sink.write(audioOut, abuf, ai)
                x.advance()
            }
        }
        try {
            while (!outputDone) {
                if (cancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        synchronized(lock) {
                            val deadline = System.currentTimeMillis() + 2500
                            while (!frameReady && System.currentTimeMillis() < deadline) lock.wait(100)
                            frameReady = false
                        }
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)
                        GLES20.glViewport(0, 0, outW, outH)
                        program.draw(oes, texMatrix, orient, crop, outW, outH, false,
                            info.presentationTimeUs / 1e6f, look, overlayTex, 0)
                        egl.setPresentationTime(encSurface, info.presentationTimeUs * 1000)
                        egl.swap(encSurface)
                        encoder.drain(false)
                        pumpAudio(info.presentationTimeUs)
                        progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    if (eos) {
                        encoder.signalEnd()
                        encoder.drain(true)
                        outputDone = true
                    }
                }
            }
            pumpAudio(Long.MAX_VALUE)
            progress(1f)
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            decoder.release()
            decSurface.release()
            st.release()
            listenerThread.quitSafely()
            encoder.release()
            sink.stop()
            GlUtil.deleteTexture(oes)
            GlUtil.deleteTexture(overlayTex)
            program.release()
            egl.releaseSurface(encSurface)
            egl.release()
            extractor.release()
            ax?.release()
        }
    }
}
