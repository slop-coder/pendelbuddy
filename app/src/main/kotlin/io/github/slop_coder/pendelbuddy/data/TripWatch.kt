package io.github.slop_coder.pendelbuddy.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.slop_coder.pendelbuddy.core.MyTrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Starts the watch of your trip [MyTrip.WATCH_BEFORE] before it leaves: right away, or by an exact alarm. */
object TripWatch {
    private const val TAG = "PendelBuddy"

    fun schedule(ctx: Context, mine: MyTrip) {
        val now = System.currentTimeMillis() / 1000
        if (mine.arrived(now)) return
        val start = mine.trip.departure - MyTrip.WATCH_BEFORE
        if (now >= start) {
            startNow(ctx)
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // Without exact alarms Android does not let the alarm start the watch; it starts when you open the app.
                Log.w(TAG, "Exact alarms not allowed")
                return
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start * 1000, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused", e)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))
        ctx.stopService(Intent(ctx, TripWatchService::class.java))
    }

    private fun startNow(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripWatchService::class.java))
        } catch (e: IllegalStateException) {
            // In the background without an exemption (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "Could not start the trip watch", e)
        }
    }

    private fun alarm(ctx: Context) = PendingIntent.getForegroundService(
        ctx, 0, Intent(ctx, TripWatchService::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Checks your trip every 5 minutes until you arrive, also with the app closed and the screen off, and shows an
 * ongoing notification meanwhile. Changes are notified by [Backend.refreshMyTrip].
 */
class TripWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val backend = Backend.get(this)
        ServiceCompat.startForeground(
            this, Alerts.WATCHING_ID, Alerts.watching(this, backend.myTrip.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        running = true
        if (job?.isActive != true) job = scope.launch { watch(backend) }
        return START_NOT_STICKY
    }

    private suspend fun watch(backend: Backend) {
        backend.load()
        while (true) {
            val mine = backend.myTrip.value ?: break
            val now = System.currentTimeMillis() / 1000
            // Arrived, or another trip taken that starts later: its alarm starts the watch again.
            if (mine.arrived(now) || now < mine.trip.departure - MyTrip.WATCH_BEFORE) break
            // The CPU stays awake only until the expected arrival (plus a little), never for hours.
            keepAwake((mine.trip.expectedArrival() - now + 10 * 60).coerceIn(60, 3 * 3600) * 1000)
            backend.refreshMyTrip("Watching", onlyIfDue = true)
            delay(CHECK_EVERY)
        }
        stopSelf()
    }

    private fun keepAwake(millis: Long) {
        val w = wake ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PendelBuddy:trip").also { it.setReferenceCounted(false); wake = it }
        w.acquire(millis)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
        /** How often to look whether a refresh is due (the refresh itself is every 5 minutes). */
        private const val CHECK_EVERY = 60_000L
    }
}
