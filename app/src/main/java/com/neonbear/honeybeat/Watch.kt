package com.neonbear.honeybeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** "New songs found" / "Downloaded" notifications. */
object Notifier {
    private const val CH = "updates"

    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (!ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE).getBoolean("notify", true)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Playlist updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

/**
 * Keeps checking the playlists that have auto-update switched on, even when the app is closed.
 * It runs as a small foreground service (with a quiet notification) while auto-update is on.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    companion object {
        private const val CH = "watch"

        /** Starts or stops the watcher so it matches the settings. */
        fun sync(ctx: Context) {
            val p = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
            val want = p.getBoolean("watch_on", false) && CaveStore.load(p).any { it.auto }
            val i = Intent(ctx, WatchService::class.java)
            if (want) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val p = getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val n = CaveStore.load(p).count { it.auto }
        val every = p.getInt("watch_every", 5)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("HoneyBeat")
            .setContentText("Watching $n playlist" + (if (n == 1) "" else "s") + ", checking every $every min")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Playlist watcher", NotificationManager.IMPORTANCE_MIN))
        ServiceCompat.startForeground(this, 2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    checkAll()
                    val every = getSharedPreferences("honeybeat", Context.MODE_PRIVATE).getInt("watch_every", 5)
                    delay(every.coerceAtLeast(1) * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun checkAll() {
        val ctx = applicationContext
        val p = getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        for (r in CaveStore.load(p).filter { it.auto }) {
            while (CaveState.running) delay(3000)
            try {
                CaveEngine.init(ctx)
                val res = resolve(ctx, r.url)
                val fresh = res.items.count { it.key !in r.keys }
                if (fresh > 0) {
                    Notifier.post(ctx, r.url.hashCode() + 1, r.name, "$fresh new song" + (if (fresh == 1) "" else "s") + " found, downloading")
                    if (CaveService.start(ctx, r.url, false)) {
                        delay(2000)
                        while (CaveState.running) delay(3000)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Starts the watcher again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WatchService.sync(context)
    }
}
