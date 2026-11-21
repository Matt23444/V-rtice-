package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Burbuja flotante (arrastrable) con un panel de controles y ajustes. */
class OverlayService : Service() {

    companion object {
        const val ACTION_CLOSE = "com.example.screenrec.CLOSE"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 2

        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var scroll: ScrollView
    private lateinit var panel: LinearLayout
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    private lateinit var recBtn: Button
    private lateinit var streamBtn: Button
    private lateinit var urlText: TextView
    private lateinit var copyBtn: Button
    private lateinit var resBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var vidBtn: Button
    private lateinit var audBtn: Button
    private lateinit var micBtn: Button
    private lateinit var audQBtn: Button
    private lateinit var sresBtn: Button
    private lateinit var sfpsBtn: Button
    private lateinit var sqBtn: Button
    private lateinit var saudBtn: Button
    private lateinit var hideBtn: Button

    private var built = false
    private var bubbleShown = false
    private var panelShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNow()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta el permiso «Mostrar sobre otras apps»", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        buildBubble()
        buildPanel()
        built = true
        running = true
        RecordService.onState = { _ -> onActiveChanged() }
        StreamService.onState = { _ -> onActiveChanged() }
        refresh()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        if (built) {
            RecordService.onState = null
            StreamService.onState = null
            hidePanel()
            hideBubble()
        }
    }

    // ------------------------------------------------------------ interfaz

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            text = "●"
        }
        bubbleLp = overlayParams(dp(48), dp(48)).apply {
            x = dp(8)
            y = dp(220)
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleLp.x; startY = bubbleLp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        bubbleLp.x = startX + dx
                        bubbleLp.y = startY + dy
                        try { wm.updateViewLayout(v, bubbleLp) } catch (ex: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    private fun makeButton(bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = roundBg(bg, 10)
        minHeight = 0
        minimumHeight = dp(44)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.addRow(v: View) {
        addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun buildPanel() {
        val grey = 0xFF3C4043.toInt()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
        }
        scroll = ScrollView(this).apply {
            background = roundBg(0xF2202124.toInt(), 18)
            addView(panel)
        }
        panelLp = overlayParams(dp(270), WindowManager.LayoutParams.WRAP_CONTENT)

        panel.addRow(TextView(this).apply {
            text = "Vórtice"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        recBtn = makeButton(0xFFD93025.toInt()) {
            if (RecordService.recording) {
                startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
                hidePanel()
            } else {
                hidePanel()
                startActivity(
                    Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        panel.addRow(recBtn)

        streamBtn = makeButton(0xFF1A73E8.toInt()) {
            if (StreamService.streaming) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
                hidePanel()
            } else {
                hidePanel()
                startActivity(
                    Intent(this, CaptureRequestActivity::class.java)
                        .putExtra("mode", "stream")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        panel.addRow(streamBtn)

        urlText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8AB4F8.toInt())
            visibility = View.GONE
        }
        panel.addRow(urlText)
        copyBtn = makeButton(grey) {
            val u = StreamService.url
            if (u != null) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Vórtice", u))
                Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
            }
        }.apply { text = "Copiar enlace"; visibility = View.GONE }
        panel.addRow(copyBtn)

        panel.addRow(header("GRABACIÓN"))
        resBtn = makeButton(grey) { Cfg.cycle(this, "res", Cfg.RES.size, Cfg.DEF_RES); refresh() }
        fpsBtn = makeButton(grey) { Cfg.cycle(this, "fps", Cfg.FPS.size, Cfg.DEF_FPS); refresh() }
        vidBtn = makeButton(grey) { Cfg.cycle(this, "video", Cfg.VIDEO_KBPS.size, Cfg.DEF_VIDEO); refresh() }
        audBtn = makeButton(grey) { Cfg.toggleAudio(this); refresh() }
        micBtn = makeButton(grey) { Cfg.toggleMic(this); refresh() }
        audQBtn = makeButton(grey) { Cfg.cycle(this, "audioq", Cfg.AUDIO_KBPS.size, Cfg.DEF_AUDIO_Q); refresh() }
        listOf(resBtn, fpsBtn, vidBtn, audBtn, micBtn, audQBtn).forEach { panel.addRow(it) }

        panel.addRow(header("TRANSMISIÓN POR WI-FI"))
        sresBtn = makeButton(grey) { Cfg.cycle(this, "sres", Cfg.STREAM_RES.size, Cfg.DEF_SRES); refresh() }
        sfpsBtn = makeButton(grey) { Cfg.cycle(this, "sfps", Cfg.STREAM_FPS.size, Cfg.DEF_SFPS); refresh() }
        sqBtn = makeButton(grey) { Cfg.cycle(this, "sq", Cfg.STREAM_Q.size, Cfg.DEF_SQ); refresh() }
        saudBtn = makeButton(grey) { Cfg.toggleStreamAudio(this); refresh() }
        listOf(sresBtn, sfpsBtn, sqBtn, saudBtn).forEach { panel.addRow(it) }

        panel.addRow(header("GENERAL"))
        hideBtn = makeButton(grey) { Cfg.toggleHideBubble(this); refresh() }
        panel.addRow(hideBtn)
        panel.addRow(makeButton(0xFF5F6368.toInt()) { stopSelf() }.apply { text = "Cerrar overlay" })
    }

    /** Actualiza textos y el aspecto según los ajustes y el estado. */
    private fun refresh() {
        if (!built) return
        val rec = RecordService.recording
        val strm = StreamService.streaming
        val busy = rec || strm

        recBtn.text = if (rec) "■  Detener grabación" else "●  Grabar"
        streamBtn.text = if (strm) "■  Detener transmisión" else "▶  Transmitir pantalla"
        recBtn.isEnabled = !strm
        recBtn.alpha = if (strm) 0.4f else 1f
        streamBtn.isEnabled = !rec
        streamBtn.alpha = if (rec) 0.4f else 1f

        val u = StreamService.url
        urlText.visibility = if (strm && u != null) View.VISIBLE else View.GONE
        copyBtn.visibility = if (strm && u != null) View.VISIBLE else View.GONE
        urlText.text = "En el otro dispositivo (misma red Wi-Fi) abre en el navegador:\n$u"

        resBtn.text = "Resolución: ${Cfg.resolution(this)}p"
        fpsBtn.text = "Fotogramas: ${Cfg.fps(this)} fps"
        vidBtn.text = "Calidad de video: ${Cfg.videoKbps(this) / 1000} Mbps"
        audBtn.text = "Audio interno: " + if (Cfg.audioOn(this)) "Sí" else "No"
        micBtn.text = "Micrófono: " + if (Cfg.micOn(this)) "Sí" else "No"
        audQBtn.text = "Calidad de audio: ${Cfg.audioKbps(this)} kbps"
        audQBtn.visibility = if (Cfg.audioOn(this) || Cfg.micOn(this)) View.VISIBLE else View.GONE

        sresBtn.text = "Resolución: ${Cfg.streamRes(this)}p"
        sfpsBtn.text = "Fluidez: ${Cfg.streamFps(this)} fps"
        sqBtn.text = "Calidad de imagen: ${Cfg.streamQualityName(this)}"
        saudBtn.text = "Audio de la transmisión: " + if (Cfg.streamAudioOn(this)) "Sí" else "No"
        hideBtn.text = "Burbuja al grabar o transmitir: " + if (Cfg.hideBubble(this)) "oculta" else "visible"

        // Mientras algo está activo no se pueden cambiar los ajustes
        listOf(resBtn, fpsBtn, vidBtn, audBtn, micBtn, audQBtn, sresBtn, sfpsBtn, sqBtn, saudBtn).forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.4f else 1f
        }

        bubble.text = if (busy) "■" else "●"
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    rec -> 0xE6D93025.toInt()
                    strm -> 0xE61A73E8.toInt()
                    else -> 0xE6202124.toInt()
                }
            )
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun onActiveChanged() {
        if (!built) return
        hidePanel()
        refresh()
        val busy = RecordService.recording || StreamService.streaming
        if (busy && Cfg.hideBubble(this)) hideBubble() else showBubble()
    }

    // ------------------------------------------------------------ ventanas

    private fun showBubble() {
        if (bubbleShown) return
        try { wm.addView(bubble, bubbleLp); bubbleShown = true } catch (ex: Exception) {}
    }

    private fun hideBubble() {
        if (!bubbleShown) return
        try { wm.removeView(bubble) } catch (ex: Exception) {}
        bubbleShown = false
    }

    private fun togglePanel() {
        if (panelShown) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelShown) return
        val dm = resources.displayMetrics
        refresh()
        panelLp.height = (dm.heightPixels * 0.8f).toInt()
        panelLp.x = (bubbleLp.x + dp(56)).coerceAtMost(dm.widthPixels - dp(270) - dp(8)).coerceAtLeast(dp(8))
        panelLp.y = dp(40)
        try { wm.addView(scroll, panelLp); panelShown = true } catch (ex: Exception) {}
    }

    private fun hidePanel() {
        if (!panelShown) return
        try { wm.removeView(scroll) } catch (ex: Exception) {}
        panelShown = false
    }

    // ------------------------------------------------------------ notificación

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val closePi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cerrar", closePi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Vórtice")
            .setContentText("Overlay activo")
            .setOngoing(true)
            .addAction(action)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
