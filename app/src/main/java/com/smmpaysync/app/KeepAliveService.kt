package com.smmpaysync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Small foreground service. It keeps the app process alive (so Android and phone
 * makers are less likely to freeze it) and sends all pending payments the moment
 * the internet comes back.
 */
class KeepAliveService : Service() {

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SMS monitor", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("SMM Payment Sync")
            .setContentText("bKash SMS monitor is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        registerNetwork()
        // flush anything that is waiting, right away
        Thread { Sender.flushPending(applicationContext, false) }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun registerNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Thread {
                        Sender.flushPending(applicationContext, false)
                        Sender.refreshUsed(applicationContext)
                    }.start()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            val cb = callback
            if (cb != null) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
