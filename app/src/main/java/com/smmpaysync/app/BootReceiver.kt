package com.smmpaysync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        try {
            SyncWorker.schedulePeriodic(app)
            SyncWorker.enqueue(app)
        } catch (e: Exception) {
        }
        try {
            if (Prefs(app).keepAlive) KeepAliveService.start(app)
        } catch (e: Exception) {
            // Android may refuse to start it from the background; the periodic worker still runs.
        }
    }
}
