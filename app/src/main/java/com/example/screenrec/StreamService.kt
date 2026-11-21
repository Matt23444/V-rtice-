package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.min

/** Transmite la pantalla (y el audio interno) por la red local para verla en un navegador. */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenrec.STREAM_START"
        const val ACTION_STOP = "com.example.screenrec.STREAM_STOP"
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 3
        private const val SAMPLE_RATE = 44100

        @Volatile var streaming = false
        @Volatile var url: String? = null
        @Volatile var onState: ((Boolean) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imgThread: HandlerThread? = null
    private var server: StreamServer? = null
    private var audioRec: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRun = false
    private var stopping = false

    private var frameBmp: Bitmap? = null
    private val baos = ByteArrayOutputStream(96 * 1024)
    private var lastFrameMs = 0L
    private var outW = 0
    private var outH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun setState(v: Boolean) {
        streaming = v
        main.post { onState?.invoke(v) }
    }

    private fun startStreaming(intent: Intent) {
        if (streaming) return
        foreground(null)
        try {
            val code = intent.getIntExtra(RecordService.EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(RecordService.EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(RecordService.EXTRA_DATA)
            }
            if (data == null) throw IllegalStateException("Sin permiso de captura")

            val proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopStreaming() } }
            }, main)
            projection = proj

            val ips = Net.localIps()
            if (ips.isEmpty()) throw IllegalStateException("Conéctate a una red Wi-Fi o activa la zona Wi-Fi")

            val dm = DisplayMetrics()
            val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION") d.getRealMetrics(dm)
            val shortSide = min(dm.widthPixels, dm.heightPixels)
            val scale = min(1f, Cfg.streamRes(this).toFloat() / shortSide)
            outW = align16(dm.widthPixels * scale)
            outH = align16(dm.heightPixels * scale)

            val audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val aRec = if (Cfg.streamAudioOn(this) && audioGranted) buildAudio(proj) else null

            val html = assets.open("viewer.html").use { it.readBytes().toString(Charsets.UTF_8) }
            val key = Net.randomKey()
            val srv = StreamServer(html, key, aRec != null, SAMPLE_RATE, 2)
            val port = srv.start()
            server = srv
            val u = "http://${ips[0]}:$port/?k=$key"
            url = u

            val ht = HandlerThread("stream-img").also { it.start() }
            imgThread = ht
            val handler = Handler(ht.looper)
            val r = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
            display = proj.createVirtualDisplay(
                "VorticeStream", outW, outH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )

            if (aRec != null) startAudio(aRec, srv)

            setState(true)
            foreground(u)
            Toast.makeText(this, "Abre $u en el navegador del otro dispositivo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo transmitir: ${e.message}", Toast.LENGTH_LONG).show()
            stopping = false
            stopStreaming()
        }
    }

    private fun onImage(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val srv = server ?: return
            val now = SystemClock.elapsedRealtime()
            // Sin espectadores solo se refresca 1 vez por segundo (ahorra CPU y batería).
            val interval = if (srv.videoClients.get() > 0) 1000L / Cfg.streamFps(this) else 1000L
            if (now - lastFrameMs < interval) return
            lastFrameMs = now

            val plane = img.planes[0]
            val padding = plane.rowStride - plane.pixelStride * outW
            val bw = outW + padding / plane.pixelStride
            var bmp = frameBmp
            if (bmp == null || bmp.width != bw || bmp.height != outH) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bw, outH, Bitmap.Config.ARGB_8888)
                frameBmp = bmp
            }
            bmp!!.copyPixelsFromBuffer(plane.buffer)
            val src = if (padding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, outW, outH)
            baos.reset()
            src.compress(Bitmap.CompressFormat.JPEG, Cfg.streamQuality(this), baos)
            if (src !== bmp) src.recycle()
            srv.pushFrame(baos.toByteArray())
        } catch (ex: Exception) {
        } finally {
            img.close()
        }
    }

    private fun buildAudio(proj: MediaProjection): AudioRecord? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
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
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
    } catch (ex: Exception) {
        null
    }

    private fun startAudio(rec: AudioRecord, srv: StreamServer) {
        audioRec = rec
        audioRun = true
        audioThread = thread(name = "stream-audio", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(4096)
            try {
                rec.startRecording()
                while (audioRun) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) srv.pushAudio(buf.copyOf(n)) else if (n < 0) break
                }
            } catch (ex: Exception) {
            } finally {
                try { rec.stop() } catch (ex: Exception) {}
            }
        }
    }

    private fun stopStreaming() {
        if (stopping) return
        stopping = true
        audioRun = false
        try { audioThread?.join(1000) } catch (ex: Exception) {}
        try { audioRec?.release() } catch (ex: Exception) {}
        audioRec = null
        try { display?.release() } catch (ex: Exception) {}
        display = null
        try { reader?.close() } catch (ex: Exception) {}
        reader = null
        try { imgThread?.quitSafely() } catch (ex: Exception) {}
        imgThread = null
        try { server?.stop() } catch (ex: Exception) {}
        server = null
        try { projection?.stop() } catch (ex: Exception) {}
        projection = null
        try { frameBmp?.recycle() } catch (ex: Exception) {}
        frameBmp = null
        url = null
        setState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) {
            stopping = false
            stopStreaming()
        }
    }

    private fun foreground(u: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transmisión", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Detener", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Transmitiendo pantalla")
            .setContentText(u ?: "Preparando…")
            .setStyle(Notification.BigTextStyle().bigText(if (u != null) "Abre en el navegador:\n$u" else "Preparando…"))
            .setOngoing(true)
            .addAction(action)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun align16(v: Float): Int = maxOf(16, ((v + 8f) / 16f).toInt() * 16)
}
