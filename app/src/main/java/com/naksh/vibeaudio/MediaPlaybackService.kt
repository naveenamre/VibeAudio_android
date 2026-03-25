package com.naksh.vibeaudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MediaPlaybackService : Service() {

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat

    private val artworkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasStartedForeground = false

    private var title: String = DEFAULT_TITLE
    private var artist: String = DEFAULT_ARTIST
    private var artworkUrl: String = ""
    private var artworkBitmap: Bitmap? = null
    private var isPlaying: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaSession.controller.transportControls.pause()
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                mediaSession.controller.transportControls.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        createNotificationChannel()
        setupMediaSession()
        registerBecomingNoisyReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_UPDATE) {
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_PLAY -> mediaSession.controller.transportControls.play()
            ACTION_PAUSE -> mediaSession.controller.transportControls.pause()
            ACTION_TOGGLE -> {
                if (isPlaying) mediaSession.controller.transportControls.pause()
                else mediaSession.controller.transportControls.play()
            }
            ACTION_NEXT -> mediaSession.controller.transportControls.skipToNext()
            ACTION_PREVIOUS -> mediaSession.controller.transportControls.skipToPrevious()
            ACTION_STOP -> stopServicePlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.release()
        artworkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaying) {
            stopServicePlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VibeAudioSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionActivity(buildContentIntent())
            setPlaybackState(buildPlaybackState(false))
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    requestAudioFocus()
                    isPlaying = true
                    acquireWakeLock()
                    PlaybackBridge.dispatch(PlaybackCommand.PLAY)
                    publishState()
                }

                override fun onPause() {
                    isPlaying = false
                    releaseWakeLock()
                    abandonAudioFocus()
                    PlaybackBridge.dispatch(PlaybackCommand.PAUSE)
                    publishState()
                }

                override fun onSkipToNext() {
                    PlaybackBridge.dispatch(PlaybackCommand.NEXT)
                    publishState()
                }

                override fun onSkipToPrevious() {
                    PlaybackBridge.dispatch(PlaybackCommand.PREVIOUS)
                    publishState()
                }

                override fun onStop() {
                    stopServicePlayback()
                }
            })
            isActive = true
        }
    }

    private fun handleUpdate(intent: Intent?) {
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { DEFAULT_TITLE }
        artist = intent?.getStringExtra(EXTRA_ARTIST).orEmpty().ifBlank { DEFAULT_ARTIST }
        val newArtworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL).orEmpty()
        isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying) ?: isPlaying

        if (isPlaying) {
            requestAudioFocus()
            acquireWakeLock()
        } else {
            releaseWakeLock()
            abandonAudioFocus()
        }

        if (newArtworkUrl != artworkUrl) {
            artworkUrl = newArtworkUrl
            artworkBitmap = null
            loadArtworkAsync(newArtworkUrl)
        } else {
            publishState()
        }
    }

    private fun publishState() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
                .build()
        )
        mediaSession.setPlaybackState(buildPlaybackState(isPlaying))

        val notification = buildNotification()
        if (!hasStartedForeground) {
            startForeground(NOTIFICATION_ID, notification)
            hasStartedForeground = true
            if (!isPlaying) {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            return
        }

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPlaybackState(isPlaying: Boolean): PlaybackStateCompat {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        return PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .build()
    }

    private fun buildNotification(): android.app.Notification {
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                buildServiceIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                buildServiceIntent(ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(artworkBitmap)
            .setContentIntent(buildContentIntent())
            .setDeleteIntent(buildServiceIntent(ACTION_STOP))
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                buildServiceIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                buildServiceIntent(ACTION_NEXT)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun loadArtworkAsync(url: String) {
        if (url.isBlank()) {
            publishState()
            return
        }

        artworkExecutor.execute {
            val bitmap = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doInput = true
                }
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            artworkBitmap = bitmap
            publishState()
        }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildServiceIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            }

            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:playback"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopServicePlayback() {
        isPlaying = false
        releaseWakeLock()
        abandonAudioFocus()
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VibeAudio Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerBecomingNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(becomingNoisyReceiver, filter)
        }
    }

    companion object {
        private const val CHANNEL_ID = "vibe_music_channel"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_TITLE = "VibeAudio"
        private const val DEFAULT_ARTIST = "Web Player"

        private const val ACTION_UPDATE = "com.naksh.vibeaudio.action.UPDATE"
        private const val ACTION_PLAY = "com.naksh.vibeaudio.action.PLAY"
        private const val ACTION_PAUSE = "com.naksh.vibeaudio.action.PAUSE"
        private const val ACTION_TOGGLE = "com.naksh.vibeaudio.action.TOGGLE"
        private const val ACTION_NEXT = "com.naksh.vibeaudio.action.NEXT"
        private const val ACTION_PREVIOUS = "com.naksh.vibeaudio.action.PREVIOUS"
        private const val ACTION_STOP = "com.naksh.vibeaudio.action.STOP"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        fun buildUpdateIntent(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String,
            isPlaying: Boolean
        ): Intent {
            return Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ARTWORK_URL, artworkUrl)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
        }
    }
}
