package com.example.screenrec

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/** Actividad transparente: muestra el diálogo del sistema para permitir la captura y se cierra. */
class CaptureRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecordService::class.java)
                .setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_CODE, resultCode)
                .putExtra(RecordService.EXTRA_DATA, data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Captura cancelada", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
