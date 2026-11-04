package com.voiceprompter.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Thin wrapper over Android's [TextToSpeech] that turns the callback API into a
 * suspending [speak] call, which is what makes the half-duplex prompter loop
 * readable: speak, then listen, then advance.
 */
class TtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceCounter = AtomicLong(0)

    @Volatile
    var isReady: Boolean = false
        private set

    suspend fun init(locale: Locale = Locale.US): Boolean =
        suspendCancellableCoroutine { continuation ->
            val engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(locale)
                    isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                } else {
                    isReady = false
                }
                if (continuation.isActive) continuation.resume(isReady)
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    pending.remove(utteranceId)?.complete(true)
                }

                @Deprecated("Required by the base class")
                override fun onError(utteranceId: String?) {
                    pending.remove(utteranceId)?.complete(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    pending.remove(utteranceId)?.complete(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    pending.remove(utteranceId)?.complete(false)
                }
            })

            tts = engine

            continuation.invokeOnCancellation { shutdown() }
        }

    /**
     * Speaks [text] and suspends until playback finishes.
     *
     * @return true if the utterance completed, false if it errored or was stopped
     */
    suspend fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return true

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)

        val id = "vp-${utteranceCounter.incrementAndGet()}"
        val signal = CompletableDeferred<Boolean>()
        pending[id] = signal

        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }

        return signal.await()
    }

    fun stop() {
        tts?.stop()
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    /**
     * Voices the installed engine actually offers.
     *
     * Note: Android does not expose voices by age or gender in any reliable
     * way. [Voice] carries a name, a locale, a quality rating and a feature
     * set — not "adult male" or "boy". Presenting an age/gender picker would
     * mean shipping a hand-maintained mapping of voice names per engine, which
     * breaks whenever the user's TTS engine updates. Showing the real voice
     * list with a preview button is the honest version of that feature.
     */
    fun availableVoices(locale: Locale = Locale.US): List<Voice> =
        tts?.voices
            ?.filter { it.locale.language == locale.language }
            ?.sortedByDescending { it.quality }
            ?: emptyList()

    fun selectVoice(voice: Voice): Boolean =
        tts?.setVoice(voice) == TextToSpeech.SUCCESS

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
