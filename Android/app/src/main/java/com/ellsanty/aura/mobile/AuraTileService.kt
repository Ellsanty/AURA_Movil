package com.ellsanty.aura.mobile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AuraTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Analizar AURA"
            state = if (CaptureService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (CaptureService.isRunning) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_ANALYZE))
            qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
        } else {
            val open = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CAPTURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(this, 710, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        }
    }
}
