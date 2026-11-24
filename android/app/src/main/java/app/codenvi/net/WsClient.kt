package app.codenvi.net

import android.util.Log
import app.codenvi.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/**
 * OkHttp WebSocket to wss://host/ws (Project.md §7.4, §10.2): runs while the app is in the foreground,
 * reconnects with exponential backoff (1 s → 30 s), refreshes the token on close code 4001.
 */
class WsClient(private val http: Http, private val store: SecureStore, private val scope: CoroutineScope) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _events = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    val events: SharedFlow<Envelope> = _events

    private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var wanted = false
    @Volatile private var closedWith: Int? = null

    private val envelopeAdapter = moshi.adapter(Envelope::class.java)

    fun start() {
        wanted = true
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 1_000L
            while (wanted) {
                if (!store.isPaired || http.revoked.value) { delay(2_000); continue }
                closedWith = null
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                try {
                    val token = http.validAccessToken()
                    val req = Request.Builder()
                        .url("wss://${store.data.serverHost}/ws?token=$token")
                        .header("X-Codenvi-Install-Id", store.data.installId)
                        .header("X-Codenvi-Client", http.clientHeader)
                        .build()
                    socket = http.baseClient().newWebSocket(req, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            _connected.value = true
                            send(MessageTypes.HELLO, HelloPayload("android"))
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val env = try { envelopeAdapter.fromJson(text) } catch (e: Exception) { null } ?: return
                            _events.tryEmit(env)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            closedWith = code
                            webSocket.close(1000, null)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.d("Codenvi", "WebSocket failure: ${t.message}")
                            done.complete(Unit)
                        }
                    })
                    done.await()
                    if (_connected.value) backoff = 1_000L
                } catch (e: RevokedException) {
                    // Http.revoked is set; the UI shows "This app was disconnected".
                } catch (e: Exception) {
                    Log.d("Codenvi", "WebSocket connect failed: ${e.message}")
                } finally {
                    _connected.value = false
                    socket = null
                }
                when (closedWith) {
                    4003 -> http.revoked.value = true
                    4001 -> { runCatching { http.validAccessToken(force = true) }; backoff = 200 }
                }
                if (!wanted) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun stop() {
        wanted = false
        socket?.close(1000, "background")
        loop?.cancel()
        loop = null
        _connected.value = false
    }

    fun restart() {
        stop()
        start()
    }

    fun <T> send(type: String, payload: T): Boolean {
        val env = Envelope(type, UUID.randomUUID().toString(), System.currentTimeMillis(), moshi.adapter(Any::class.java).toJsonValue(payload))
        return socket?.send(envelopeAdapter.toJson(env)) ?: false
    }
}
