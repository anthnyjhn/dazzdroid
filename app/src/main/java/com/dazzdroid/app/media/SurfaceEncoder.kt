package com.dazzdroid.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/** H.264 encoder fed by a GL window surface. Drained synchronously by the GL thread. */
class SurfaceEncoder(val width: Int, val height: Int, bitRate: Int, fps: Int, private val sink: MuxerSink) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private var track = -1
    private val info = MediaCodec.BufferInfo()
    var finished = false
        private set

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun signalEnd() {
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
    }

    /** Pull everything available out of the encoder. With [endOfStream], blocks until EOS. */
    fun drain(endOfStream: Boolean) {
        if (finished) return
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++tries > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = sink.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && track >= 0) sink.write(track, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        finished = true
                        return
                    }
                }
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        inputSurface.release()
    }

    companion object {
        fun bitrateFor(w: Int, h: Int): Int = (w * h * 30 * 0.2).toInt().coerceIn(2_000_000, 16_000_000)
    }
}
