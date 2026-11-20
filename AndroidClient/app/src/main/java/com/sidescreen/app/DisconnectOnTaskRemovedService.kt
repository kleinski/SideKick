package com.sidescreen.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Lightweight non-foreground service whose sole purpose is receiving
 * [onTaskRemoved] when the user swipes SideKick away or taps "Close all" in
 * Recents, ensuring the active stream socket closes cleanly and immediately
 * notifies the Mac to tear down the virtual display.
 */
class DisconnectOnTaskRemovedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        onTaskRemovedCallback?.invoke()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        @Volatile
        var onTaskRemovedCallback: (() -> Unit)? = null
    }
}
