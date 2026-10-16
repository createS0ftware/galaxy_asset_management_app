package com.galaxy.assettrail.driver.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.galaxy.assettrail.core.platform.OfflineException
import com.galaxy.assettrail.core.platform.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A view model whose actions report failures as one short message for the driver. */
abstract class ActionViewModel : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    protected fun say(text: String) {
        _message.value = text
    }

    protected fun action(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun friendly(e: Throwable): String = when (e) {
            is OfflineException -> "No signal. Try again when you're back online."
            is PlatformException -> e.message ?: "Something went wrong. Try again."
            is IllegalStateException, is IllegalArgumentException -> e.message ?: "That can't be done right now."
            else -> "Something went wrong. Try again."
        }
    }
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar host") }

/** Shows the view model's message once as a snackbar. */
@Composable
fun MessageEffect(viewModel: ActionViewModel) {
    val snackbar = LocalSnackbar.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.clearMessage()
        snackbar.showSnackbar(text)
    }
}
