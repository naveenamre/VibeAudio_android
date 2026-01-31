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
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var assetLoader: WebViewAssetLoader

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "vibe_music_channel"
    private val PERMISSION_REQUEST_CODE = 101

    // 🔥 FINAL PRODUCTION URL
    private val APP_URL = "https://vibeaudio.pages.dev/frontend/src/pages/app.html?source=android"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        // Local Server with CORS Fix
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/local/", CORSPathHandler(this, this.filesDir))
            .build()

        setupMediaSession()
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
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("VibeWeb", "${consoleMessage?.message()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request!!.url) ?: super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
                webView.evaluateJavascript("document.body.classList.add('is-android');", null)
            }
        }

        webView.loadUrl(APP_URL)
    }

    // --- CORS HANDLER ---
    private class CORSPathHandler(context: Context, dir: File) : WebViewAssetLoader.PathHandler {
        private val internalHandler = WebViewAssetLoader.InternalStoragePathHandler(context, dir)
        override fun handle(path: String): WebResourceResponse? {
            val response = internalHandler.handle(path)
            if (response != null) {
                val headers = response.responseHeaders?.toMutableMap() ?: mutableMapOf()
                headers["Access-Control-Allow-Origin"] = "*"
                headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
                response.responseHeaders = headers
            }
            return response
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VibeAudioSession")
        mediaSession.isActive = true
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { runOnUiThread { webView.evaluateJavascript("window.app.togglePlay()", null) } }
            override fun onPause() { runOnUiThread { webView.evaluateJavascript("window.app.togglePlay()", null) } }
            override fun onSkipToNext() { runOnUiThread { webView.evaluateJavascript("window.app.nextChapter()", null) } }
            override fun onSkipToPrevious() { runOnUiThread { webView.evaluateJavascript("window.app.prevChapter()", null) } }
        })
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun checkFile(fileName: String): String {
            val file = File(context.filesDir, fileName)
            return if (file.exists()) "https://appassets.androidplatform.net/local/$fileName" else ""
        }

        @JavascriptInterface
        fun downloadFile(urlStr: String, fileName: String, callbackName: String) {
            Executors.newSingleThreadExecutor().execute {
                try {
                    val url = URL(urlStr)
                    val file = File(context.filesDir, fileName)
                    url.openStream().use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                    val virtualPath = "https://appassets.androidplatform.net/local/$fileName"
                    Handler(Looper.getMainLooper()).post { webView.evaluateJavascript("$callbackName(true, '$virtualPath');", null) }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post { webView.evaluateJavascript("$callbackName(false, '');", null) }
                }
            }
        }

        @JavascriptInterface
        fun deleteFile(fileName: String) { try { File(context.filesDir, fileName).delete() } catch (e: Exception) {} }

        @JavascriptInterface
        fun updateMediaNotification(title: String, artist: String, imageUrl: String, isPlaying: Boolean) {
            Executors.newSingleThreadExecutor().execute {
                var art: Bitmap? = null
                try {
                    val url = URL(imageUrl)
                    art = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                } catch (e: Exception) {}
                Handler(Looper.getMainLooper()).post {
                    updateMediaSessionState(title, artist, art, isPlaying)
                    showNotification(title, artist, art, isPlaying)
                }
            }
        }
    }

    private fun updateMediaSessionState(title: String, artist: String, art: Bitmap?, isPlaying: Boolean) {
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            .build())
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build())
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, artist: String, bitmap: Bitmap?, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(bitmap)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (isPlaying) { builder.addAction(android.R.drawable.ic_media_pause, "Pause", null) }
        else { builder.addAction(android.R.drawable.ic_media_play, "Play", null) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VibeAudio Player", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
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