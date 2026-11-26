package app.codenvi

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.codenvi.data.SecureStore
import app.codenvi.data.SettingsStore
import app.codenvi.db.AppDatabase
import app.codenvi.net.Http
import app.codenvi.notify.ListenService
import app.codenvi.notify.Notifier
import app.codenvi.repo.Repository
import app.codenvi.speech.ReadAloud
import app.codenvi.speech.WhisperManager
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Manual DI (docs/design.md §10.2: no Hilt). */
class AppContainer(app: Application) {
    val secure = SecureStore(app)
    val settings = SettingsStore(app)
    val db = AppDatabase.create(app)
    val http = Http(secure)
    val repository = Repository(secure, settings, db, http)
    val readAloud by lazy { ReadAloud(app) }
    val notifier = Notifier(app)
    val whisper = WhisperManager(app)
}

class CodenviApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.repository.onReply = { container.notifier.replyArrived(it) }

        // Reply sound and notifications (docs/design.md §10.7): while the phone is paired and the setting is on, a small foreground
        // service keeps the connection open in the background; otherwise the connection closes when the app leaves the screen.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            combine(container.secure.flow, container.settings.flow) { secure, settings ->
                (secure.refreshToken != null && secure.serverHost != null && settings.notifyReplies)
            }.distinctUntilChanged().collect { want ->
                listenWanted = want
                container.notifier.enabled = want
                syncListenService()
            }
        }
        // The WebSocket runs while the app is in the foreground, and in the background too when [listenWanted] (docs/design.md §10.2).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.repository.ws.start(); syncListenService() }
            override fun onStop(owner: LifecycleOwner) { if (!listenWanted) container.repository.ws.stop() }
        })
    }

    @Volatile private var listenWanted = false

    /** Starts the background service when wanted (only works while the app is on screen, which is when this is called). */
    private fun syncListenService() {
        val intent = Intent(this, ListenService::class.java)
        if (listenWanted) runCatching { ContextCompat.startForegroundService(this, intent) } else stopService(intent)
    }
}
