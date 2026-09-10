package com.example.screencapture

import android.content.Intent
import android.service.quicksettings.TileService

class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        @Suppress("DEPRECATION")
        startActivityAndCollapse(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}