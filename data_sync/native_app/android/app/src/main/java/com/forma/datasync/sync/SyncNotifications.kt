package com.forma.datasync.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Generic operational notices; never expose source names, records, URLs, or credentials. */
object SyncNotifications {
    private const val CHANNEL = "forma-sync-status"
    private const val ID = 7433

    fun failure(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Data sync status", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val intent = PendingIntent.getActivity(context, ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Forma sync needs attention")
            .setContentText("Open Forma to review your connection, permissions, or sign-in.")
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }

    fun clear(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}
