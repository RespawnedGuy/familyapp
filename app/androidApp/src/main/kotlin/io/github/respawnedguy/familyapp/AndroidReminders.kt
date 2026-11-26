package io.github.respawnedguy.familyapp

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.domain.ReminderPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.Instant
import java.time.ZoneId

/**
 * Android runner for [ReminderPlanner] (quality plan P1-1): one inexact alarm for the next
 * reminder, a notification when it fires, rescheduled after every change and after a reboot.
 * Inexact on purpose (setAndAllowWhileIdle): a few minutes late at 8 o'clock is fine and needs
 * no "exact alarm" permission.
 */
class AndroidReminders(private val context: Context, private val repo: FamilyRepository) {
    private val planner = ReminderPlanner()
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    /** Keeps the alarm in step with the data while the process lives. */
    @OptIn(FlowPreview::class)
    fun follow(scope: CoroutineScope) {
        scope.launch {
            // Only what changes the plan, not every keystroke; the debounce bundles quick edits.
            repo.data.map { planner.all(it) to it.settings.remindersOn }.distinctUntilChanged()
                .debounce(DEBOUNCE_MS).collect { schedule() }
        }
    }

    /** Plans the next alarm. The first time, marks "now" as handled so old deadlines stay quiet. */
    fun schedule() {
        val nowMs = System.currentTimeMillis()
        if (repo.current.settings.remindersCheckedMs == null) repo.recordRemindersChecked(nowMs)
        val next = planner.check(repo.current, checked = local(nowMs), now = local(nowMs)).next
        setAlarm(next)
    }

    /** The alarm fired (or the phone restarted): show what is due, then plan the next alarm. */
    fun run() {
        val nowMs = System.currentTimeMillis()
        val data = repo.current
        val checked = data.settings.remindersCheckedMs?.let(::local)
        val result = planner.check(data, checked, local(nowMs))
        result.show?.let { show(it.title, it.text) }
        repo.recordRemindersChecked(nowMs)
        setAlarm(result.next)
    }

    private fun setAlarm(at: LocalDateTime?) {
        val pending = alarmIntent()
        if (at == null) {
            alarms.cancel(pending)
        } else {
            val ms = at.toJavaLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
        }
    }

    private fun show(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, launch, flags)
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        // One fixed id: a newer reminder replaces the old one instead of piling up.
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Fristen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Erinnert am Tag der Frist und wenn eine Frist abgelaufen ist."
            enableVibration(false) // design decision: no vibration, visual feedback only
        }
        notifications.createNotificationChannel(channel)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun local(ms: Long): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().toKotlinLocalDateTime()

    companion object {
        const val ACTION_REMIND = "io.github.respawnedguy.familyapp.REMIND"
        private const val CHANNEL_ID = "deadlines"
        private const val NOTIFICATION_ID = 8
        private const val DEBOUNCE_MS = 2_000L
    }
}

/** Alarm, reboot and app update all end here; the data lives in [FamilyApplication]. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FamilyApplication
        when (intent.action) {
            AndroidReminders.ACTION_REMIND, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val done = goAsync()
                app.reminders.run()
                // The repository writes on its IO scope; give that write a moment before the
                // process may be stopped. A lost write only means the same notification once more.
                app.scope.launch {
                    delay(WRITE_GRACE_MS)
                    done.finish()
                }
            }
        }
    }

    private companion object {
        const val WRITE_GRACE_MS = 1_500L
    }
}
