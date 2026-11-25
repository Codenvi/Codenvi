package app.codenvi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.codenvi.CodenviApp
import app.codenvi.repo.Repository
import app.codenvi.repo.RevokedOrUser
import app.codenvi.ui.components.Toast
import app.codenvi.ui.components.ToastKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Thin shared view model: runs repository calls, turns failures into toast messages.
 * Screens read the repository's flows directly.
 */
class AppViewModel(val repo: Repository) : ViewModel() {
    private val _messages = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val messages: SharedFlow<Toast> = _messages

    fun say(text: String, kind: ToastKind = ToastKind.Info) { _messages.tryEmit(Toast(text, kind)) }
    fun ok(text: String) = say(text, ToastKind.Success)
    fun warn(text: String) = say(text, ToastKind.Warning)
    fun fail(text: String) = say(text, ToastKind.Error)

    /** Runs [block]; on failure shows a readable message. [onOk] runs on success. */
    fun run(onOk: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onOk?.invoke()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                fail(RevokedOrUser.message(e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(RevokedOrUser.message(e))
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CodenviApp
                AppViewModel(app.container.repository)
            }
        }
    }
}
