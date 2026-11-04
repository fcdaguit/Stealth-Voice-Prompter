package com.voiceprompter.speech

import android.util.Log
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Wraps Vosk's microphone service as a cold [Flow] of recognition events.
 *
 * Vosk gives a streaming API with partial hypotheses, which is what lets the
 * prompter highlight progress through a phrase rather than waiting for the
 * speaker to stop talking.
 */
class VoskRecognizer(private val modelDir: File) {

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16_000f
    }

    sealed interface Event {
        /** Interim hypothesis; revised as the speaker continues. */
        data class Partial(val text: String) : Event

        /** Settled hypothesis for an utterance. */
        data class Final(val text: String) : Event

        data class Failed(val cause: Throwable) : Event

        data object SilenceTimeout : Event
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null

    /**
     * Starts the microphone and emits events until the flow is cancelled.
     *
     * Requires RECORD_AUDIO to have been granted already — Vosk will throw
     * otherwise, and the exception surfaces as [Event.Failed].
     */
    fun listen(): Flow<Event> = callbackFlow {
        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                extract(hypothesis, "partial")?.let { trySend(Event.Partial(it)) }
            }

            override fun onResult(hypothesis: String?) {
                extract(hypothesis, "text")?.let { trySend(Event.Final(it)) }
            }

            override fun onFinalResult(hypothesis: String?) {
                extract(hypothesis, "text")?.let { trySend(Event.Final(it)) }
            }

            override fun onError(exception: Exception?) {
                val cause = exception ?: IllegalStateException("Unknown recogniser error")
                Log.e(TAG, "Recognition error", cause)
                trySend(Event.Failed(cause))
            }

            override fun onTimeout() {
                trySend(Event.SilenceTimeout)
            }
        }

        try {
            val loaded = Model(modelDir.absolutePath)
            model = loaded
            val recognizer = Recognizer(loaded, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start recogniser", t)
            trySend(Event.Failed(t))
            close(t)
        }

        awaitClose { release() }
    }

    /**
     * Stops the microphone without tearing down the model.
     *
     * Call this before TTS plays. In half-duplex (practice) mode the mic must
     * be off while the app speaks, or the recogniser transcribes the app's own
     * voice and the script runs away on its own.
     */
    fun pause(paused: Boolean) {
        speechService?.setPause(paused)
    }

    fun release() {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { model?.close() }
        model = null
    }

    private fun extract(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            JSONObject(json).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
