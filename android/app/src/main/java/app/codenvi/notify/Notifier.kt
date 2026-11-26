package app.codenvi.notify

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.codenvi.MainActivity
import app.codenvi.R

object Channels {
    /** Versioned: a channel's sound cannot be changed once created, so a new sound needs a new id. */
    const val REPLIES = "replies_v1"
    const val LISTEN = "listen_v1"
}

const val EXTRA_SESSION_ID = "app.codenvi.extra.SESSION_ID"

/** A finished reply, described without anything secret beyond what the phone has already decrypted. */
data class ReplyInfo(val sessionId: String, val messageId: String, val projectName: String, val sessionName: String, val preview: String)

/**
 * The incoming-reply sound and pop-up (docs/design.md §10.7).
 * - App open (any page): the Codenvi chime plays, no pop-up (you are already looking at the app).
 * - App in the background: a heads-up notification with the project name, the session name and a preview of the reply, which
 *   plays the same chime through its channel. Tapping it opens that session's chat.
 */
class Notifier(private val app: Application) {
    /** Turned off in Settings → no sound and no notification. */
    @Volatile var enabled = true

    private val recent = LinkedHashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    init { createChannels() }

    private fun soundUri(): Uri = Uri.parse("android.resource://${app.packageName}/${R.raw.reply_chime}")

    private fun createChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(Channels.REPLIES, "Replies", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A reply from Claude Code arrived in one of your sessions"
            setSound(soundUri(), attrs)
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(Channels.LISTEN, "Waiting for replies", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Quiet status while Codenvi keeps its connection open in the background"
            setShowBadge(false)
        })
    }

    private val inForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun replyArrived(info: ReplyInfo) {
        if (!enabled) return
        synchronized(recent) {
            if (!recent.add(info.messageId)) return // already announced (a resent result)
            while (recent.size > 100) recent.remove(recent.first())
        }
        if (inForeground) playChime() else post(info)
    }

    /** In-app sound, on the notification volume (silent when the phone is silent or vibrating). */
    private fun playChime() = main.post {
        val audio = app.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@post
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        runCatching {
            MediaPlayer.create(app, R.raw.reply_chime, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE)?.apply {
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                start()
            }
        }
    }

    private fun post(info: ReplyInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, info.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = android.app.PendingIntent.getActivity(
            app, info.sessionId.hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val line = if (info.preview.isBlank()) info.sessionName else "${info.sessionName}: ${info.preview}"
        // On a locked screen only a generic text shows (the project and session names are private).
        val generic = NotificationCompat.Builder(app, Channels.REPLIES)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Codenvi").setContentText("New reply").build()
        val n = NotificationCompat.Builder(app, Channels.REPLIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(info.projectName)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(info.sessionId.hashCode(), n) }
    }
}
