package com.neonbear.honeybeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs Fetch downloads as a foreground service so Android keeps them alive in the background. */
class CaveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CH = "downloads"
        private const val ACTION_CANCEL = "cancel"

        private fun launch(ctx: Context, url: String?, auto: Boolean, refetch: Boolean = false) {
            val i = Intent(ctx, CaveService::class.java).putExtra("auto", auto).putExtra("refetch", refetch)
            if (url != null) i.putExtra("url", url)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Clone / sync a playlist link. Returns false when another download is already running. */
        fun start(ctx: Context, url: String, auto: Boolean, refetch: Boolean = false): Boolean {
            val go = synchronized(CaveQueue) {
                if (CaveState.running) false else { CaveState.running = true; true }
            }
            if (go) launch(ctx, url, auto, refetch)
            return go
        }

        /** Queue one song from the search. Starts the service if nothing is running. */
        fun queueTrack(ctx: Context, id: String, title: String) {
            val go = synchronized(CaveQueue) {
                CaveQueue.add(Item("yt:$id", "https://www.youtube.com/watch?v=$id", title))
                if (CaveState.running) false else { CaveState.running = true; true }
            }
            CaveState.addLog("queued: $title")
            if (go) launch(ctx, null, false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, CaveService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HoneyBeat")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", cancel)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            CaveJob.cancel()
            return START_NOT_STICKY
        }
        if (intent == null) {
            CaveState.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra("url")
        val auto = intent.getBooleanExtra("auto", false)
        val refetch = intent.getBooleanExtra("refetch", false)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val progress = { text: String -> nm.notify(1, notification(text)) }
        scope.launch {
            try {
                if (url != null) CaveJob.run(applicationContext, url, auto, refetch, progress)
                while (true) {
                    val items = CaveQueue.take()
                    if (items.isEmpty()) {
                        val stop = synchronized(CaveQueue) {
                            if (CaveQueue.isEmpty()) { CaveState.running = false; true } else false
                        }
                        if (stop) break else continue
                    }
                    CaveJob.runItems(applicationContext, items, progress)
                }
            } finally {
                CaveState.running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
