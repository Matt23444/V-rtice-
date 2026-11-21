package com.example.screenrec

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Process
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Graba pantalla (H.264 por hardware) + audio interno (AAC) directamente a un MP4.
 * No usa librerías externas: solo las APIs nativas de Android.
 */
class Recorder(
    private val projection: MediaProjection,
    private val fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val videoBitrate: Int,
    private val withAudio: Boolean,
    private val audioBitrate: Int,
) {
    private class Sample(val isVideo: Boolean, val data: ByteArray, val pts: Long, val flags: Int)

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
    }

    private val lock = Any()
    private val pending = ArrayList<Sample>()

    private var muxer: MediaMuxer? = null
    private var vTrack = -1
    private var aTrack = -1
    private var muxerStarted = false

    @Volatile private var running = false
    private var audioOn = false

    private var originUs = 0L
    private var vBaseUs = Long.MIN_VALUE
    private var lastV = -1L
    private var lastA = -1L

    private var vCodec: MediaCodec? = null
    private var aCodec: MediaCodec? = null
    private var aRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    fun start() {
        originUs = System.nanoTime() / 1000
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        audioOn = withAudio && setupAudio()
        setupVideo()
        running = true
        vThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
        if (audioOn) aThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
    }

    // ---------------------------------------------------------------- video

    private fun setupVideo() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Si la pantalla está quieta, repite el último fotograma para no dejar huecos.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        vCodec = codec
        inputSurface = surface
        display = projection.createVirtualDisplay(
            "ScreenRec", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
    }

    private fun videoLoop() {
        val codec = vCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) {
                        vTrack = muxer!!.addTrack(codec.outputFormat)
                        tryStart()
                    }
                } else if (i >= 0) {
                    val buf = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) emit(true, buf, info)
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (ex: Exception) {
        }
    }

    // ---------------------------------------------------------------- audio

    private fun setupAudio(): Boolean {
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val af = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(af)
                .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            aRecord = rec

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            aCodec = codec
            true
        } catch (ex: Exception) {
            aRecord?.release()
            aRecord = null
            false
        }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = aRecord ?: return
        val codec = aCodec ?: return
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(4096)
        var frames = 0L
        var baseUs = -1L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord error $n")
                if (n > 0) {
                    val chunkFrames = n / (2 * CHANNELS)
                    if (baseUs < 0) {
                        baseUs = (System.nanoTime() / 1000 - originUs -
                            chunkFrames * 1_000_000L / SAMPLE_RATE).coerceAtLeast(0L)
                    }
                    val pts = baseUs + frames * 1_000_000L / SAMPLE_RATE
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        ib.put(buf, 0, n)
                        codec.queueInputBuffer(idx, 0, n, pts, 0)
                        frames += chunkFrames
                    }
                }
                drainAudio(codec, info, 0)
            }
            // Fin de flujo
            val endPts = (if (baseUs < 0) 0L else baseUs) + frames * 1_000_000L / SAMPLE_RATE
            var queued = false
            var tries = 0
            while (!queued && tries++ < 50) {
                val idx = codec.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            tries = 0
            while (tries++ < 100) {
                if (drainAudio(codec, info, 10_000)) break
            }
        } catch (ex: Exception) {
            // Si el audio falla, se sigue con video solamente.
            synchronized(lock) {
                audioOn = false
                tryStart()
            }
        } finally {
            try { rec.stop() } catch (ex: Exception) {}
        }
    }

    /** Devuelve true si llegó el fin de flujo. */
    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    aTrack = muxer!!.addTrack(codec.outputFormat)
                    tryStart()
                }
            } else if (i >= 0) {
                val buf = codec.getOutputBuffer(i)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) emit(false, buf, info)
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            } else {
                return false
            }
        }
    }

    // ---------------------------------------------------------------- muxer

    private fun emit(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            var pts: Long
            if (isVideo) {
                if (vBaseUs == Long.MIN_VALUE) {
                    // Normalmente la marca de tiempo del video usa el mismo reloj que nanoTime().
                    val d = info.presentationTimeUs - originUs
                    vBaseUs = if (d in -2_000_000L..10_000_000L) originUs else info.presentationTimeUs
                }
                pts = (info.presentationTimeUs - vBaseUs).coerceAtLeast(0L)
                if (pts <= lastV) pts = lastV + 1
                lastV = pts
            } else {
                pts = info.presentationTimeUs.coerceAtLeast(0L)
                if (pts <= lastA) pts = lastA + 1
                lastA = pts
            }

            buf.position(info.offset)
            buf.limit(info.offset + info.size)

            if (!muxerStarted) {
                val copy = ByteArray(info.size)
                buf.get(copy)
                pending.add(Sample(isVideo, copy, pts, info.flags))
                return
            }
            val bi = MediaCodec.BufferInfo()
            bi.set(info.offset, info.size, pts, info.flags)
            try {
                muxer!!.writeSampleData(if (isVideo) vTrack else aTrack, buf, bi)
            } catch (ex: Exception) {
            }
        }
    }

    /** Debe llamarse con [lock] tomado. Arranca el muxer cuando están listas las pistas. */
    private fun tryStart() {
        if (muxerStarted) return
        if (vTrack >= 0 && (!audioOn || aTrack >= 0)) {
            muxer!!.start()
            muxerStarted = true
            val bi = MediaCodec.BufferInfo()
            for (s in pending) {
                bi.set(0, s.data.size, s.pts, s.flags)
                try {
                    muxer!!.writeSampleData(if (s.isVideo) vTrack else aTrack, ByteBuffer.wrap(s.data), bi)
                } catch (ex: Exception) {
                }
            }
            pending.clear()
        }
    }

    // ---------------------------------------------------------------- stop

    fun stop() {
        if (!running) return
        running = false
        try { display?.release() } catch (ex: Exception) {}
        try { vCodec?.signalEndOfInputStream() } catch (ex: Exception) {}
        try { aThread?.join(3000) } catch (ex: Exception) {}
        try { vThread?.join(3000) } catch (ex: Exception) {}

        try { vCodec?.stop() } catch (ex: Exception) {}
        try { vCodec?.release() } catch (ex: Exception) {}
        try { aCodec?.stop() } catch (ex: Exception) {}
        try { aCodec?.release() } catch (ex: Exception) {}
        try { aRecord?.release() } catch (ex: Exception) {}
        try { inputSurface?.release() } catch (ex: Exception) {}

        synchronized(lock) {
            try { if (muxerStarted) muxer?.stop() } catch (ex: Exception) {}
            try { muxer?.release() } catch (ex: Exception) {}
            muxerStarted = false
        }
    }
}
