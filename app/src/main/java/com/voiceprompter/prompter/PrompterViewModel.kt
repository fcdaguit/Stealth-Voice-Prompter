package com.voiceprompter.prompter

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceprompter.match.MatchMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A thin UI-facing shell over [PrompterService].
 *
 * The engine itself lives in the service, not here, so that it survives the
 * screen turning off and the Activity going away. This class only binds,
 * mirrors the engine's state for Compose, and forwards commands.
 *
 * Commands issued before the binding completes are not lost: [start] carries
 * the script in the Intent, and the other controls are no-ops until bound,
 * which is correct since there is nothing running to control.
 */
class PrompterViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PrompterState())
    val state: StateFlow<PrompterState> = _state.asStateFlow()

    private var engine: PrompterEngine? = null
    private var mirrorJob: Job? = null
    private var bound = false

    private var scriptBody: String = ""
    private var scriptTitle: String = "Script"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? PrompterService.LocalBinder ?: return
            val boundEngine = localBinder.engine
            engine = boundEngine

            // If the service was already running with a script loaded, its
            // state wins — the user may be returning to a session in progress.
            mirrorJob?.cancel()
            mirrorJob = boundEngine.state
                .onEach { _state.value = it }
                .launchIn(viewModelScope)

            if (boundEngine.state.value.phrases.isEmpty() && scriptBody.isNotBlank()) {
                boundEngine.loadScript(scriptBody)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            mirrorJob?.cancel()
        }
    }

    init {
        bind()
    }

    private fun bind() {
        if (bound) return
        val context = getApplication<Application>()
        bound = context.bindService(
            Intent(context, PrompterService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    /**
     * Holds the script for the next [start]. Does not touch a session already
     * in progress — reopening the screen mid-session should not reset it.
     */
    fun loadScript(body: String, title: String = "Script") {
        scriptBody = body
        scriptTitle = title

        val current = engine
        if (current == null) {
            _state.value = _state.value.copy(
                phrases = com.voiceprompter.match.ScriptSegmenter.segment(body),
                currentIndex = 0,
            )
            return
        }
        if (!current.state.value.isRunning) {
            current.loadScript(body)
        }
    }

    /**
     * Starts the session as a foreground service so it keeps running with the
     * screen off. Call only after RECORD_AUDIO has been granted — a
     * microphone-type foreground service throws without it.
     */
    fun start() {
        val context = getApplication<Application>()
        val intent = Intent(context, PrompterService::class.java).apply {
            action = PrompterService.ACTION_START
            putExtra(PrompterService.EXTRA_SCRIPT, scriptBody)
            putExtra(PrompterService.EXTRA_TITLE, scriptTitle)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        bind()
    }

    fun pause() = engine?.pause() ?: Unit

    fun resume() = engine?.resume() ?: Unit

    fun next() = engine?.next() ?: Unit

    fun previous() = engine?.previous() ?: Unit

    fun setMatchMode(mode: MatchMode) {
        engine?.setMatchMode(mode)
        if (engine == null) _state.value = _state.value.copy(matchMode = mode)
    }

    fun setVolumeKeysScroll(enabled: Boolean) {
        engine?.setVolumeKeysScroll(enabled)
        if (engine == null) _state.value = _state.value.copy(volumeKeysScroll = enabled)
    }

    fun setSpeechRate(rate: Float) {
        engine?.setSpeechRate(rate)
        if (engine == null) _state.value = _state.value.copy(speechRate = rate)
    }

    fun setPitch(pitch: Float) {
        engine?.setPitch(pitch)
        if (engine == null) _state.value = _state.value.copy(pitch = pitch)
    }

    /** Ends the session and tears the service down. */
    fun stop() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, PrompterService::class.java).setAction(PrompterService.ACTION_STOP),
        )
    }

    override fun onCleared() {
        // Unbind only. The service keeps running on purpose: the user closing
        // the screen is not the user ending the session.
        mirrorJob?.cancel()
        if (bound) {
            runCatching { getApplication<Application>().unbindService(connection) }
            bound = false
        }
        engine = null
        super.onCleared()
    }
}
