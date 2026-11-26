package org.freeagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The "a new Lille is out" notification (v0.12.1):
 * posted at most once per version (UpdateCheck's
 * shouldNotify is the gate; UpdateChecker records what
 * was notified). Tapping it opens the one-tap APK link
 * in the browser — the stable signing line makes that
 * download install over the installed app, data intact.
 * Best-effort, like WallNotify: if notifications are not
 * granted, the in-app banner still carries the news.
 */
object UpdateNotify {

    private const val CHANNEL_ID = "lille_updates"
    private const val NOTIFICATION_ID = 3001

    fun notifyUpdate(context: Context, remote: UpdateCheck.RemoteVersion) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Lille updates",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.APK_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            val text = UpdateCheck.notificationText(remote)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("A new Lille is out")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // The banner still shows in the app; a failed
            // doorbell never fails anything else.
        }
    }
}
