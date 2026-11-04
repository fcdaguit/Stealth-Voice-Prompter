package com.voiceprompter.prompter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import com.voiceprompter.MainActivity
import com.voiceprompter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the prompter running when the screen is off or the app is in the
 * background.
 *
 * Three separate mechanisms are needed, and dropping any one of them breaks
 * screen-off operation in a different way:
 *
 *  1. **Foreground service with `microphone` type.** Since Android 11 a
 *     backgrounded app gets silence from the microphone rather than an error,
 *     which would look like the recogniser simply never matching. On Android
 *     14+ the matching `FOREGROUND_SERVICE_MICROPHONE` permission is required
 *     as well, and `startForeground` throws without it.
 *
 *  2. **Partial wake lock.** A foreground service exempts the app from most
 *     Doze restrictions but does not stop the CPU suspending with the screen
 *     off. Without the lock, recognition stalls mid-phrase and resumes only
 *     when something else wakes the device.
 *
 *  3. **Audio focus.** So the TTS ducks music and, more importantly, so the
 *     app surrenders gracefully to a phone call rather than talking over it.
 *
 * The service is started from the Activity while it is in the foreground.
 * Starting a microphone-type foreground service *from* the background is
 * itself restricted, so this ordering matters.
 */
class PrompterService : Service() {

    companion object {
        const val ACTION_START = "com.voiceprompter.START"
        const val ACTION_TOGGLE_PAUSE = "com.voiceprompter.TOGGLE_PAUSE"
        const val ACTION_NEXT = "com.voiceprompter.NEXT"
        const val ACTION_PREVIOUS = "com.voiceprompter.PREVIOUS"
        const val ACTION_STOP = "com.voiceprompter.STOP"

        const val EXTRA_SCRIPT = "script"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "prompter"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "VoicePrompter::session"
    }

    inner class LocalBinder : Binder() {
        val engine: PrompterEngine get() = this@PrompterService.engine
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var engine: PrompterEngine
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var scriptTitle: String = "Script"
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        engine = PrompterEngine(applicationContext, scope)
        createNotificationChannel()
        setUpMediaSession()

        engine.state
            .onEach { state ->
                if (startedForeground) {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                }
                updatePlaybackState(state)

                // Grab the volume keys only while there is a live session to
                // control, so they behave normally the rest of the time.
                applyVolumeKeyRouting(
                    capture = state.volumeKeysScroll &&
                        (state.isRunning || state.status == PrompterStatus.PAUSED),
                )

                // Release the device once the work is genuinely over.
                if (state.status == PrompterStatus.FINISHED ||
                    state.status == PrompterStatus.ERROR
                ) {
                    releaseWakeLock()
                    abandonAudioFocus()
                }
            }
            .launchIn(scope)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { scriptTitle = it }
                intent.getStringExtra(EXTRA_SCRIPT)?.let { engine.loadScript(it) }
                goForeground()
                acquireWakeLock()
                requestAudioFocus()
                engine.start()
            }
            ACTION_TOGGLE_PAUSE -> engine.togglePause()
            ACTION_NEXT -> engine.next()
            ACTION_PREVIOUS -> engine.previous()
            ACTION_STOP -> {
                engine.stop()
                stopSelf()
            }
        }
        // Do not auto-restart with a null intent: there would be no script to
        // resume and the user would get a silent, stuck notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.shutdown()
        releaseWakeLock()
        abandonAudioFocus()
        // Hand the volume keys back before tearing the session down, or they
        // can stay captured until the framework notices the session is gone.
        applyVolumeKeyRouting(capture = false)
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    // --- foreground + notification ---------------------------------------

    private fun goForeground() {
        if (startedForeground) return
        val notification = buildNotification(engine.state.value)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedForeground = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Prompter session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a script is running"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(state: PrompterState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val statusLine = when (state.status) {
            PrompterStatus.PREPARING -> "Preparing…"
            PrompterStatus.SPEAKING -> "Reading aloud"
            PrompterStatus.LISTENING -> "Listening"
            PrompterStatus.AD_LIB -> "Off script — holding"
            PrompterStatus.PAUSED -> "Paused"
            PrompterStatus.FINISHED -> "Finished"
            PrompterStatus.ERROR -> state.error ?: "Error"
            PrompterStatus.IDLE -> "Ready"
        }

        val position = if (state.phrases.isNotEmpty()) {
            " · ${state.currentIndex + 1}/${state.phrases.size}"
        } else {
            ""
        }

        // Title carries the status, body carries the current line, so the
        // notification is readable on a lock screen without opening the app.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$scriptTitle · $statusLine$position")
            .setContentText(state.currentPhrase?.text ?: "")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(state.isRunning)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Back", servicePendingIntent(ACTION_PREVIOUS, 1))
            .addAction(
                0,
                if (state.status == PrompterStatus.PAUSED) "Resume" else "Pause",
                servicePendingIntent(ACTION_TOGGLE_PAUSE, 2),
            )
            .addAction(0, "Skip", servicePendingIntent(ACTION_NEXT, 3))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PrompterService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // --- media buttons ----------------------------------------------------

    /**
     * Bluetooth presentation remotes and clickers almost all present as media
     * key sources. A MediaSession is the only way to receive those keys while
     * the screen is off — [MainActivity.onKeyDown] only fires while the app is
     * in the foreground with the screen on.
     */
    private fun setUpMediaSession() {
        mediaSession = MediaSessionCompat(this, "VoicePrompter").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = engine.resume()
                override fun onPause() = engine.pause()
                override fun onSkipToNext() = engine.next()
                override fun onSkipToPrevious() = engine.previous()
                override fun onStop() {
                    engine.stop()
                    stopSelf()
                }

                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val event = IntentCompatGetKeyEvent(intent) ?: return false
                    if (event.action != KeyEvent.ACTION_DOWN) return false
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_PAGE_DOWN,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        -> { engine.next(); true }

                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_PAGE_UP,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        -> { engine.previous(); true }

                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        -> { engine.togglePause(); true }

                        else -> false
                    }
                }
            })
            isActive = true
        }
    }

    @Suppress("DEPRECATION", "FunctionName")
    private fun IntentCompatGetKeyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    // --- volume keys ------------------------------------------------------

    /**
     * Routes the hardware volume keys to the script instead of the volume
     * stream, and keeps doing so with the screen off.
     *
     * `Activity.onKeyDown` cannot do this: it only fires while the activity
     * has window focus, so it dies the moment the screen turns off. Declaring
     * the MediaSession's playback as *remote* makes the system deliver volume
     * key presses to this [VolumeProviderCompat] instead, which works from the
     * lock screen.
     *
     * The trade-off is real and unavoidable: while this is active the volume
     * keys no longer change the volume, so the earpiece level has to be set
     * before starting or changed from the notification shade. That is why it
     * is a toggle rather than always-on.
     *
     * Direction follows the spec: Volume Up scrolls up (previous phrase),
     * Volume Down scrolls down (next phrase).
     */
    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_RELATIVE,
        /* maxVolume = */ 2,
        /* currentVolume = */ 1,
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> engine.previous()
                direction < 0 -> engine.next()
            }
            // Pin back to the middle. If the reported volume reaches 0 or max
            // the framework stops sending further adjustments in that
            // direction, and scrolling would silently stop working.
            currentVolume = 1
        }
    }

    private var volumeKeysCaptured = false

    private fun applyVolumeKeyRouting(capture: Boolean) {
        if (capture == volumeKeysCaptured) return
        val session = mediaSession ?: return

        if (capture) {
            session.setPlaybackToRemote(volumeProvider)
        } else {
            session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        }
        volumeKeysCaptured = capture
    }

    private fun updatePlaybackState(state: PrompterState) {
        val playbackState = when {
            state.isRunning -> PlaybackStateCompat.STATE_PLAYING
            state.status == PrompterStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    // --- wake lock + audio focus ------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Timeout as a safety net: if the service somehow leaks, the lock
            // dies after four hours rather than draining the battery flat.
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> engine.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.pause()
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
