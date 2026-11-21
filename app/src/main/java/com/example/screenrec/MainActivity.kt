package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Pantalla de inicio: pide los permisos y enciende o apaga el overlay. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Vórtice"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Graba tu pantalla con el audio interno. Ligero y sin anuncios."
            textSize = 15f
            setPadding(0, 0, 0, pad)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, pad)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Conceder permisos"
            isAllCaps = false
            setOnClickListener { requestPerms() }
        })
        toggle = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleOverlay() }
        }
        root.addView(toggle)

        root.addView(TextView(this).apply {
            text = "\nCómo se usa: enciende el overlay y abre lo que quieras grabar. " +
                "Toca la burbuja ● para grabar o ajustar la calidad. " +
                "Si la burbuja se oculta durante la grabación, detén desde la notificación. " +
                "Los videos se guardan en Películas/ScreenRec."
            textSize = 14f
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val audio = has(Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        fun mark(ok: Boolean) = if (ok) "✔" else "✘"
        status.text = "${mark(audio)} Permiso de audio\n" +
            "${mark(notif)} Notificaciones\n" +
            "${mark(overlay)} Mostrar sobre otras apps"
        toggle.text = if (OverlayService.running) "Apagar overlay" else "Encender overlay"
    }

    private fun requestPerms() {
        val missing = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            openOverlaySettings()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (!Settings.canDrawOverlays(this)) openOverlaySettings()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            refreshStatus()
            return
        }
        Toast.makeText(this, "Activa «Permitir mostrar sobre otras apps»", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            toggle.text = "Encender overlay"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestPerms()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }
}
