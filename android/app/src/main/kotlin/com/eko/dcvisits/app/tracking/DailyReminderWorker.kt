package com.eko.dcvisits.app.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eko.dcvisits.app.MainActivity
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.util.AppLocale
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.geo.haversineMeters
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Two pop-ups a day for DCs: a morning nudge to Check In (with today's pending
 * CSPs and the nearest one) and an evening nudge to End Day. Purely local — it
 * reads the cached CSP list and today's attendance ops, so it works offline and
 * needs no server change. Skips silently for non-DC roles or when signed out.
 */
class DailyReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT)?.let { runCatching { ReminderSlot.valueOf(it) }.getOrNull() }
            ?: return Result.success()
        val session = ServiceLocator.sessionStore.current() ?: return Result.success()
        if (session.user.role != "DC") return Result.success()

        val csps = ServiceLocator.cspRepository
        csps.refresh() // best effort; the cache keeps serving offline
        val today = Ist.today()
        val all = csps.all()
        val pending = all.filter { it.lastVisitDate != today }
        val fix = LocationProvider.currentFix(applicationContext)
        val nearest = fix?.let { f ->
            pending.filter { it.lat != 0.0 || it.lng != 0.0 }
                .map { it to haversineMeters(f.lat, f.lng, it.lat, it.lng) }
                .minByOrNull { it.second }
        }

        val copy = DailyReminderText.forSlot(
            ReminderSnapshot(
                slot = slot,
                day = ServiceLocator.attendanceRepository.localDayState.first(),
                cspsAssigned = all.size,
                visitedToday = all.size - pending.size,
                nearestPendingCode = nearest?.first?.code,
                nearestPendingMeters = nearest?.second,
            ),
            StringLookup.of(AppLocale.localized(applicationContext)),
        ) ?: return Result.success()

        notify(slot, copy)
        return Result.success()
    }

    private fun notify(slot: ReminderSlot, copy: NotificationCopy) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val str = StringLookup.of(AppLocale.localized(ctx))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, str.get(R.string.channel_reminders_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = str.get(R.string.channel_reminders_desc)
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(copy.title)
            .setContentText(copy.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.details.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIFICATION_ID_BASE + slot.ordinal, n)
    }

    companion object {
        private const val KEY_SLOT = "slot"
        private const val CHANNEL = "daily_reminders"
        private const val NOTIFICATION_ID_BASE = 2100

        /** IST times of the two pop-ups. */
        private val TIMES = mapOf(
            ReminderSlot.MORNING to LocalTime.of(9, 0),
            ReminderSlot.EVENING to LocalTime.of(18, 0),
        )

        /** Idempotent: call on every app start; existing schedules are kept. */
        fun scheduleAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            TIMES.forEach { (slot, at) ->
                val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(Duration.ofDays(1))
                    .setInitialDelay(delayUntil(at).toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_SLOT to slot.name))
                    .build()
                wm.enqueueUniquePeriodicWork("daily-reminder-${slot.name.lowercase()}", ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        internal fun delayUntil(at: LocalTime, now: ZonedDateTime = ZonedDateTime.now(Ist.ZONE)): Duration {
            var next = now.with(at).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
