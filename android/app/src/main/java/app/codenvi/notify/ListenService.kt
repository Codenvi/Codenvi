package app.codenvi.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.codenvi.CodenviApp
import app.codenvi.MainActivity
import app.codenvi.R

/**
 * Keeps the WebSocket to the user's own server open while the app is in the background, so a reply that finishes while another
 * app is in front can still be announced (docs/design.md §10.7). Android requires a visible (here: silent, minimal) notification
 * for that. It runs only while the phone is paired and "Reply sound and notifications" is on in Settings.
 */
class ListenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Channels.LISTEN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Codenvi is waiting for replies")
            .setContentText("Tap to open")
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf() // Android refused (for example a start from the background): the app starts it again when it is opened.
            return START_NOT_STICKY
        }
        (application as CodenviApp).container.repository.ws.start()
        return START_STICKY
    }
}
