package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Burbuja flotante (arrastrable) con un panel de controles y ajustes de calidad. */
class OverlayService : Service() {

    companion object {
        const val ACTION_CLOSE = "com.example.screenrec.CLOSE"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 2

        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var recBtn: Button
    private lateinit var resBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var vidBtn: Button
    private lateinit var audBtn: Button
    private lateinit var micBtn: Button
    private lateinit var audQBtn: Button
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
        RecordService.onState = { rec -> onRecState(rec) }
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

    private fun buildPanel() {
        val grey = 0xFF3C4043.toInt()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
            background = roundBg(0xF2202124.toInt(), 18)
        }
        panelLp = overlayParams(dp(260), WindowManager.LayoutParams.WRAP_CONTENT)

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

        resBtn = makeButton(grey) { Cfg.cycle(this, "res", Cfg.RES.size, Cfg.DEF_RES); refresh() }
        fpsBtn = makeButton(grey) { Cfg.cycle(this, "fps", Cfg.FPS.size, Cfg.DEF_FPS); refresh() }
        vidBtn = makeButton(grey) { Cfg.cycle(this, "video", Cfg.VIDEO_KBPS.size, Cfg.DEF_VIDEO); refresh() }
        audBtn = makeButton(grey) { Cfg.toggleAudio(this); refresh() }
        micBtn = makeButton(grey) { Cfg.toggleMic(this); refresh() }
        audQBtn = makeButton(grey) { Cfg.cycle(this, "audioq", Cfg.AUDIO_KBPS.size, Cfg.DEF_AUDIO_Q); refresh() }
        hideBtn = makeButton(grey) { Cfg.toggleHideBubble(this); refresh() }
        listOf(resBtn, fpsBtn, vidBtn, audBtn, micBtn, audQBtn, hideBtn).forEach { panel.addRow(it) }

        panel.addRow(makeButton(0xFF5F6368.toInt()) { stopSelf() }.apply { text = "Cerrar overlay" })
    }

    /** Actualiza textos y el aspecto según los ajustes y el estado de grabación. */
    private fun refresh() {
        if (!built) return
        val rec = RecordService.recording
        recBtn.text = if (rec) "■  Detener grabación" else "●  Grabar"
        resBtn.text = "Resolución: ${Cfg.resolution(this)}p"
        fpsBtn.text = "Fotogramas: ${Cfg.fps(this)} fps"
        vidBtn.text = "Calidad de video: ${Cfg.videoKbps(this) / 1000} Mbps"
        audBtn.text = "Audio interno: " + if (Cfg.audioOn(this)) "Sí" else "No"
        micBtn.text = "Micrófono: " + if (Cfg.micOn(this)) "Sí" else "No"
        audQBtn.text = "Calidad de audio: ${Cfg.audioKbps(this)} kbps"
        audQBtn.visibility = if (Cfg.audioOn(this) || Cfg.micOn(this)) View.VISIBLE else View.GONE
        hideBtn.text = "Burbuja al grabar: " + if (Cfg.hideBubble(this)) "oculta" else "visible"

        // Mientras se graba no se puede cambiar la calidad
        listOf(resBtn, fpsBtn, vidBtn, audBtn, micBtn, audQBtn).forEach {
            it.isEnabled = !rec
            it.alpha = if (rec) 0.4f else 1f
        }

        bubble.text = if (rec) "■" else "●"
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (rec) 0xE6D93025.toInt() else 0xE6202124.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        if (panelShown) {
            try { wm.updateViewLayout(panel, panelLp) } catch (ex: Exception) {}
        }
    }

    private fun onRecState(rec: Boolean) {
        if (!built) return
        hidePanel()
        refresh()
        if (rec && Cfg.hideBubble(this)) hideBubble() else showBubble()
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
        panelLp.x = (bubbleLp.x + dp(56)).coerceAtMost(dm.widthPixels - dp(260) - dp(8)).coerceAtLeast(dp(8))
        panelLp.y = bubbleLp.y.coerceAtMost(dm.heightPixels - dp(520)).coerceAtLeast(dp(24))
        refresh()
        try { wm.addView(panel, panelLp); panelShown = true } catch (ex: Exception) {}
    }

    private fun hidePanel() {
        if (!panelShown) return
        try { wm.removeView(panel) } catch (ex: Exception) {}
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
