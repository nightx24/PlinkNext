package io.github.augztn.pocketlink.ssdbridge

import android.app.Service
import android.os.Build

@Suppress("DEPRECATION")
internal fun Service.stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    } else {
        stopForeground(true)
    }
}
