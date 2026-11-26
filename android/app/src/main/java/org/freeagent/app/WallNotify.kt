package org.freeagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The "Lille needs you" notification (v0.9): posted when a
 * browser task parks at a human-only wall (PARKED_AT_WALL).
 * Tapping it opens BrowserActivity — her parked session,
 * full-screen — where the ONE named step happens. The same
 * step is completable in the chat itself, on the in-chat
 * browser panel where the park was announced; the
 * notification is the doorbell, not the only surface.
 * Best-effort: if notifications are not granted, the park
 * still stands in the chat.
 */
object WallNotify {

    private const val CHANNEL_ID = "lille_needs_you"

    fun notifyParked(context: Context, taskId: String, stepText: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Lille needs you",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val intent = Intent(context, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_TASK_ID, taskId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            val pending = PendingIntent.getActivity(
                context, taskId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Lille needs you")
                .setContentText(stepText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(stepText))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(2000 + (taskId.hashCode() and 0x3FF), notification)
        } catch (e: Exception) {
            // The park is recorded in the TaskStore regardless;
            // a failed doorbell never fails the task.
        }
    }
}
