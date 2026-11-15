package com.dazzdroid.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Thread-safe MediaMuxer wrapper. Starts once [expectedTracks] are added, buffering samples that
 * arrive earlier, and rebases timestamps so the file starts at zero.
 */
class MuxerSink(path: String, private var expectedTracks: Int, orientationHint: Int = 0) {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var started = false
    private var added = 0
    private var stopped = false
    private var baseUs = Long.MIN_VALUE
    private val lastPts = HashMap<Int, Long>()
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()

    init {
        muxer.setOrientationHint(orientationHint)
    }

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val idx = muxer.addTrack(format)
        added++
        maybeStart()
        return idx
    }

    /** A producer that will never deliver its track (e.g. microphone unavailable). */
    @Synchronized
    fun abandonTrack() {
        expectedTracks--
        maybeStart()
    }

    private fun maybeStart() {
        if (!started && added > 0 && added >= expectedTracks) {
            muxer.start()
            started = true
            for ((t, data, info) in pending) writeInternal(t, ByteBuffer.wrap(data), info)
            pending.clear()
        }
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (!started) {
            val copy = ByteArray(info.size)
            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(copy)
            val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(track, copy, i)
            return
        }
        writeInternal(track, buf, info)
    }

    private fun writeInternal(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (baseUs == Long.MIN_VALUE) baseUs = info.presentationTimeUs
        var pts = info.presentationTimeUs - baseUs
        if (pts < 0) return
        val last = lastPts[track]
        if (last != null && pts <= last) pts = last + 1
        lastPts[track] = pts
        val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
        buf.position(info.offset); buf.limit(info.offset + info.size)
        muxer.writeSampleData(track, buf, out)
    }

    /** Set the base explicitly (e.g. the recording start time) before any samples are written. */
    @Synchronized
    fun setBaseUs(us: Long) { if (baseUs == Long.MIN_VALUE) baseUs = us }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        try {
            if (started) muxer.stop()
        } catch (_: Exception) {
        }
        muxer.release()
    }

    val hasStarted: Boolean @Synchronized get() = started
}
