package com.dazzdroid.app.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder

/** Microphone -> AAC on its own thread, written into a shared [MuxerSink]. */
class AudioCapture(private val sink: MuxerSink) {
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "dazz-audio")

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.join(3000)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            sink.abandonTrack()
            return
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var eosSent = false
        var gotFormat = false
        var totalSamples = 0L
        val startNs = System.nanoTime()
        try {
            while (true) {
                if (!eosSent) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val read = record.read(buf, minOf(buf.capacity(), 4096))
                        val pts = (startNs / 1000) + totalSamples * 1_000_000L / rate
                        if (!running) {
                            codec.queueInputBuffer(inIdx, 0, maxOf(read, 0), pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, maxOf(read, 0), pts, 0)
                        }
                        if (read > 0) totalSamples += read / 2
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = sink.addTrack(codec.outputFormat)
                    gotFormat = true
                } else if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && track >= 0) sink.write(track, ob, info)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (_: Exception) {
            if (!gotFormat) sink.abandonTrack()
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }
}
