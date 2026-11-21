package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenrec.START"
        const val ACTION_STOP = "com.example.screenrec.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1

        @Volatile var recording = false

        /** Lo usa el overlay para enterarse de los cambios de estado. */
        @Volatile var onState: ((Boolean) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: Recorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun setState(v: Boolean) {
        recording = v
        main.post { onState?.invoke(v) }
    }

    private fun startRecording(intent: Intent) {
        if (recording) return
        val audioPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val micWanted = Cfg.micOn(this) && audioPerm
        val micAllowed = startForegroundNow(micWanted)
        if (micWanted && !micAllowed) {
            Toast.makeText(this, "Android no permitió usar el micrófono; se graba sin él", Toast.LENGTH_LONG).show()
        }

        try {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) throw IllegalStateException("Sin permiso de captura")

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            // Obligatorio en Android 14: registrar el callback antes de crear la pantalla virtual.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopRecording() }
                }
            }, main)
            projection = proj

            // Tamaño de la grabación según el ajuste (sin ampliar nunca la pantalla real)
            val dm = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION") display.getRealMetrics(dm)
            val shortSide = min(dm.widthPixels, dm.heightPixels)
            val scale = min(1f, Cfg.resolution(this).toFloat() / shortSide)
            val w = align16(dm.widthPixels * scale)
            val h = align16(dm.heightPixels * scale)

            // Archivo de salida en Películas/ScreenRec
            val name = "ScreenRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No se pudo crear el archivo")
            uri = u
            val p = contentResolver.openFileDescriptor(u, "rw")
                ?: throw IllegalStateException("No se pudo abrir el archivo")
            pfd = p

            val audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val rec = Recorder(
                projection = proj,
                fd = p.fileDescriptor,
                width = w,
                height = h,
                dpi = dm.densityDpi,
                fps = Cfg.fps(this),
                videoBitrate = Cfg.videoKbps(this) * 1000,
                withAudio = Cfg.audioOn(this) && audioGranted,
                withMic = micAllowed,
                audioBitrate = Cfg.audioKbps(this) * 1000,
            )
            rec.start()
            recorder = rec
            setState(true)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar: ${e.message}", Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        try { recorder?.stop() } catch (ex: Exception) {}
        recorder = null
        try { projection?.stop() } catch (ex: Exception) {}
        projection = null
        try { pfd?.close() } catch (ex: Exception) {}
        pfd = null
        uri?.let {
            try {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(it, v, null, null)
                Toast.makeText(this, "Grabación guardada en Películas/ScreenRec", Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {}
        }
        uri = null
        setState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) {
            stopping = false
            stopRecording()
        }
    }

    private fun startForegroundNow(mic: Boolean): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Grabación", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Detener", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Grabando pantalla")
            .setContentText("Toca «Detener» para terminar")
            .setOngoing(true)
            .addAction(action)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            startForeground(NOTIF_ID, n, type)
            mic
        } catch (ex: SecurityException) {
            if (!mic) throw ex
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            false
        }
    }

    private fun align16(v: Float): Int = maxOf(16, ((v + 8f) / 16f).toInt() * 16)
}
