package io.github.slop_coder.pendelbuddy.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.slop_coder.pendelbuddy.MainActivity
import io.github.slop_coder.pendelbuddy.R
import io.github.slop_coder.pendelbuddy.core.Change
import io.github.slop_coder.pendelbuddy.core.MyTrip
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.format.DateTimeFormatter

/** Notifications about your trip: the ongoing one while it is watched, and one when it changes. */
object Alerts {
    private const val WATCHING = "watching"
    private const val CHANGES = "changes"
    const val WATCHING_ID = 1
    private const val CHANGES_ID = 2

    /** Extras of the intent that opens the app from a notification. */
    const val OPEN = "open"
    const val OPEN_MY_TRIP = "my-trip"
    const val OPEN_ALTERNATIVES = "alternatives"
    const val STATION_ID = "stationId"
    const val STATION_NAME = "stationName"
    const val AT = "at"

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(WATCHING, "Watching your trip", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the trip you take is checked for changes"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANGES, "Trip changes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cancelled trains, big delays, new platforms, lost connections"
            },
        )
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The ongoing notification: your first train, its delay and platform. */
    fun watching(ctx: Context, mine: MyTrip?): Notification {
        channels(ctx)
        val first = mine?.trip?.trains?.firstOrNull()
        val text = when {
            mine == null || first == null -> "Your trip"
            else -> {
                val late = first.departureDelay?.takeIf { it > 0 }?.let { " +$it min" }.orEmpty()
                "${first.line} ${hm(first.departure)}$late · ${first.fromName}" + (first.platform?.let { " · Pl. $it" } ?: "")
            }
        }
        val age = mine?.fetchedAt?.takeIf { it > 0 }?.let { "Updated ${hm(it)}" } ?: "Not downloaded yet"
        return NotificationCompat.Builder(ctx, WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(age)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, OPEN_MY_TRIP, null, null, 0))
            .build()
    }

    /** Refreshes the ongoing notification, if the watch is running. */
    fun updateWatching(ctx: Context, mine: MyTrip) {
        if (TripWatchService.running && allowed(ctx)) notify(ctx, WATCHING_ID, watching(ctx, mine))
    }

    /** One notification for the new [changes]; tapping it (or "Alternatives") shows other trips from where it happens. */
    fun changed(ctx: Context, mine: MyTrip, changes: List<Change>) {
        if (!allowed(ctx)) return
        channels(ctx)
        val replan = changes.firstOrNull { it.station != null && it.at != null }
        val tap = replan?.let { open(ctx, OPEN_ALTERNATIVES, it.station, it.at, 1) } ?: open(ctx, OPEN_MY_TRIP, null, null, 0)
        val n = NotificationCompat.Builder(ctx, CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(changes.first().text)
            .setContentText(if (changes.size > 1) "and ${changes.size - 1} more" else "Your trip at ${hm(mine.trip.departure)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(changes.joinToString("\n") { it.text }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .apply { if (replan != null) addAction(0, "Alternatives", tap) }
            .addAction(0, "My trip", open(ctx, OPEN_MY_TRIP, null, null, 0))
            .build()
        notify(ctx, CHANGES_ID, n)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // Permission taken away in the meantime: the app still shows the changes.
        }
    }

    private fun open(ctx: Context, what: String, station: Station?, at: Long?, request: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(OPEN, what)
        if (station != null && at != null) {
            i.putExtra(STATION_ID, station.id).putExtra(STATION_NAME, station.name).putExtra(AT, at)
        }
        return PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun hm(t: Long) = t.toSwiss().format(HM)
}
