package app.codenvi

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.codenvi.data.SecureStore
import app.codenvi.data.SettingsStore
import app.codenvi.db.AppDatabase
import app.codenvi.net.Http
import app.codenvi.repo.Repository
import app.codenvi.speech.ReadAloud

/** Manual DI (Project.md §10.2: no Hilt). */
class AppContainer(app: Application) {
    val secure = SecureStore(app)
    val settings = SettingsStore(app)
    val db = AppDatabase.create(app)
    val http = Http(secure)
    val repository = Repository(secure, settings, db, http)
    val readAloud by lazy { ReadAloud(app) }
}

class CodenviApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The WebSocket runs while the app is in the foreground (Project.md §10.2).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.repository.ws.start()
            override fun onStop(owner: LifecycleOwner) = container.repository.ws.stop()
        })
    }
}
