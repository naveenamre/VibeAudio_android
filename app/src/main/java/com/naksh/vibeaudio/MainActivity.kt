package com.naksh.vibeaudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // 🎵 MEDIA SESSION (Ye hai Lock Screen ka raaz)
    private lateinit var mediaSession: MediaSessionCompat

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "vibe_music_channel"
    private val PERMISSION_REQUEST_CODE = 101

    private val APP_URL = "https://miniature-space-funicular-7vxpg9q945r9fxvxv-5502.app.github.dev/frontend/src/pages/app.html?source=android"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        // 1. Initialize MediaSession
        mediaSession = MediaSessionCompat(this, "VibeAudioSession")
        mediaSession.isActive = true // System ko bolo hum active hain

        createNotificationChannel()
        checkAndRequestPermissions()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("VibeWeb", "${consoleMessage?.message()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webView.evaluateJavascript("document.body.classList.add('is-android');", null)
            }
        }

        webView.loadUrl(APP_URL)
    }

    // --- 🌉 JS BRIDGE ---
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun updateMediaNotification(title: String, artist: String, imageUrl: String, isPlaying: Boolean) {
            Executors.newSingleThreadExecutor().execute {
                var art: Bitmap? = null
                try {
                    val url = URL(imageUrl)
                    art = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                } catch (e: Exception) {
                    Log.e("VibeWeb", "Image Load Error: ${e.message}")
                }

                Handler(Looper.getMainLooper()).post {
                    updateMediaSessionState(title, artist, art, isPlaying)
                    showNotification(title, artist, art, isPlaying)
                }
            }
        }
    }

    // --- 🎵 UPDATE MEDIA SESSION STATE ---
    // Android System ko batana padta hai ki hum play kar rahe hain ya pause
    private fun updateMediaSessionState(title: String, artist: String, art: Bitmap?, isPlaying: Boolean) {
        // 1. Metadata Set Karo (Lock Screen Background & Info ke liye)
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) // Lock Screen BG
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            .build())

        // 2. Playback State (Taaki system ko pata chale Play/Pause kya hai)
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build())
    }

    // --- 🔔 SHOW NOTIFICATION ---
    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, artist: String, bitmap: Bitmap?, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Notification Action Icons
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", pendingIntent)
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", pendingIntent)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(bitmap)
            // 🔥 CRITICAL: Ye line Notification ko Media Style banati hai aur MediaSession jodti hai
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken) // <-- Iske bina Lock Screen nahi aayega
                .setShowActionsInCompactView(0))
            .addAction(playPauseAction) // Play/Pause Button
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying) // Play hote waqt sticky
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Lock Screen Visible

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VibeAudio Player", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Media controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
            }
        }
    }
}