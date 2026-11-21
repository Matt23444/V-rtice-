package com.example.screenrec

import android.content.Context

/** Ajustes guardados. Cada opción se guarda como un índice dentro de su lista. */
object Cfg {
    val RES = intArrayOf(480, 720, 1080)                 // lado corto, en píxeles
    val FPS = intArrayOf(24, 30, 60)
    val VIDEO_KBPS = intArrayOf(1000, 2000, 3000, 5000, 8000)
    val AUDIO_KBPS = intArrayOf(64, 96, 128, 192)

    // Valores por defecto pensados para un equipo modesto: 720p, 30 fps, 3 Mbps, AAC 96 kbps
    const val DEF_RES = 1
    const val DEF_FPS = 1
    const val DEF_VIDEO = 2
    const val DEF_AUDIO_Q = 1

    private fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun idx(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)

    fun cycle(c: Context, key: String, size: Int, def: Int): Int {
        val next = (idx(c, key, def) + 1) % size
        prefs(c).edit().putInt(key, next).apply()
        return next
    }

    fun audioOn(c: Context): Boolean = prefs(c).getBoolean("audio", true)
    fun toggleAudio(c: Context): Boolean {
        val v = !audioOn(c)
        prefs(c).edit().putBoolean("audio", v).apply()
        return v
    }

    /** true = la burbuja se oculta mientras se graba (así no sale en el video). */
    fun hideBubble(c: Context): Boolean = prefs(c).getBoolean("hide", true)
    fun toggleHideBubble(c: Context): Boolean {
        val v = !hideBubble(c)
        prefs(c).edit().putBoolean("hide", v).apply()
        return v
    }

    fun resolution(c: Context) = RES[idx(c, "res", DEF_RES)]
    fun fps(c: Context) = FPS[idx(c, "fps", DEF_FPS)]
    fun videoKbps(c: Context) = VIDEO_KBPS[idx(c, "video", DEF_VIDEO)]
    fun audioKbps(c: Context) = AUDIO_KBPS[idx(c, "audioq", DEF_AUDIO_Q)]
}
