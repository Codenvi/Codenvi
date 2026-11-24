package app.codenvi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.codenvi.CodenviApp
import app.codenvi.repo.Repository
import app.codenvi.repo.RevokedOrUser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Thin shared view model: runs repository calls, turns failures into snackbar messages.
 * Screens read the repository's flows directly.
 */
class AppViewModel(val repo: Repository) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun say(text: String) { _messages.tryEmit(text) }

    /** Runs [block]; on failure shows a readable message. [onOk] runs on success. */
    fun run(onOk: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onOk?.invoke()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                say(RevokedOrUser.message(e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                say(RevokedOrUser.message(e))
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
