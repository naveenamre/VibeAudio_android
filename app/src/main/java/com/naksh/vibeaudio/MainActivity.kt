package com.naksh.vibeaudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullScreenContainer: FrameLayout
    private lateinit var assetLoader: WebViewAssetLoader

    private val downloadExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val appUrl = "https://vibeaudio.pages.dev/frontend/src/pages/app.html?source=android"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/local/", CORSPathHandler(this, filesDir))
            .build()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        fullScreenContainer = findViewById(R.id.fullScreenContainer)

        PlaybackBridge.attach(webView)

        requestNotificationPermission()
        configureWebView()
        configureBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(appUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        PlaybackBridge.attach(webView)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        PlaybackBridge.detach(webView)
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            userAgentString = "$userAgentString VibeAudioAndroid/1.1"
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("VibeWeb", consoleMessage?.message().orEmpty())
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val protectedMedia = request?.resources
                    ?.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    ?.toTypedArray()
                    .orEmpty()

                if (protectedMedia.isNotEmpty()) {
                    request?.grant(protectedMedia)
                } else {
                    request?.deny()
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress < 100
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val intercepted = request?.url?.let(assetLoader::shouldInterceptRequest)
                return intercepted ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                return when (url.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> openExternal(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.isVisible = false
                CookieManager.getInstance().flush()
                injectAndroidPlaybackSupport()
                webView.evaluateJavascript("document.body && document.body.classList.add('is-android');", null)
            }
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        fullScreenContainer.removeAllViews()
        fullScreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullScreenContainer.isVisible = true
        webView.isVisible = false
        progressBar.isVisible = false

        WindowInsetsControllerCompat(window, fullScreenContainer).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hideCustomView() {
        val activeCustomView = customView ?: return

        fullScreenContainer.removeView(activeCustomView)
        fullScreenContainer.isVisible = false
        customView = null
        webView.isVisible = true
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        WindowInsetsControllerCompat(window, webView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun injectAndroidPlaybackSupport() {
        val script = """
            (() => {
              if (window.__vibeAndroidBridgeInstalled) {
                return true;
              }
              window.__vibeAndroidBridgeInstalled = true;

              const patch = (target, key, value) => {
                try {
                  Object.defineProperty(target, key, {
                    configurable: true,
                    get: () => value
                  });
                } catch (_) {}
              };

              patch(document, "hidden", false);
              patch(document, "webkitHidden", false);
              patch(document, "visibilityState", "visible");
              patch(document, "webkitVisibilityState", "visible");
              try {
                document.hasFocus = () => true;
              } catch (_) {}

              const getPrimaryMedia = () => {
                const candidates = Array.from(document.querySelectorAll("video, audio"));
                return candidates.find((media) => !media.paused) || candidates[0] || null;
              };

              const syncNativeNotification = (media) => {
                if (!window.AndroidInterface || typeof AndroidInterface.updateMediaNotification !== "function" || !media) {
                  return;
                }
                const title = document.title || media.getAttribute("title") || "VibeAudio";
                const artist = location.hostname.replace(/^www\./, "") || "Web Player";
                const artwork = media.getAttribute("poster") || "";
                try {
                  AndroidInterface.updateMediaNotification(title, artist, artwork, !media.paused && !media.ended);
                } catch (_) {}
              };

              const bindMedia = (media) => {
                if (!media || media.dataset.vibeAndroidBound === "1") {
                  return;
                }
                media.dataset.vibeAndroidBound = "1";
                ["play", "pause", "ended", "loadedmetadata"].forEach((eventName) => {
                  media.addEventListener(eventName, () => syncNativeNotification(media));
                });
              };

              const scanMedia = () => {
                Array.from(document.querySelectorAll("video, audio")).forEach(bindMedia);
                const activeMedia = getPrimaryMedia();
                if (activeMedia) {
                  syncNativeNotification(activeMedia);
                }
              };

              scanMedia();
              new MutationObserver(scanMedia).observe(document.documentElement || document.body, {
                childList: true,
                subtree: true
              });
              return true;
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun postJavascriptCallback(callbackName: String, success: Boolean, payload: String) {
        if (!CALLBACK_NAME_REGEX.matches(callbackName)) {
            Log.w("VibeDownload", "Ignored unsafe callback: $callbackName")
            return
        }

        val script = "$callbackName($success, ${JSONObject.quote(payload)});"
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun dispatchPlaybackState(title: String, artist: String, artworkUrl: String, isPlaying: Boolean) {
        val playbackIntent = MediaPlaybackService.buildUpdateIntent(
            context = this,
            title = title.ifBlank { getString(R.string.app_name) },
            artist = artist.ifBlank { "Web Player" },
            artworkUrl = artworkUrl,
            isPlaying = isPlaying
        )
        ContextCompat.startForegroundService(this, playbackIntent)
    }

    private inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun checkFile(fileName: String): String {
            return OfflineAudioStore.resolveVirtualUrl(context, fileName).orEmpty()
        }

        @JavascriptInterface
        fun downloadFile(urlStr: String, fileName: String, callbackName: String) {
            val referer = webView.url.orEmpty()
            val userAgent = webView.settings.userAgentString
            downloadExecutor.execute {
                val result = OfflineAudioStore.download(
                    context = context,
                    sourceUrl = urlStr,
                    requestedFileName = fileName,
                    userAgent = userAgent,
                    referer = referer
                )
                postJavascriptCallback(
                    callbackName = callbackName,
                    success = result.isSuccess,
                    payload = result.getOrNull().orEmpty()
                )
            }
        }

        @JavascriptInterface
        fun deleteFile(fileName: String) {
            OfflineAudioStore.delete(context, fileName)
        }

        @JavascriptInterface
        fun updateMediaNotification(title: String, artist: String, imageUrl: String, isPlaying: Boolean) {
            dispatchPlaybackState(title, artist, imageUrl, isPlaying)
        }
    }

    private class CORSPathHandler(context: Context, dir: File) : WebViewAssetLoader.PathHandler {
        private val internalHandler = WebViewAssetLoader.InternalStoragePathHandler(context, dir)

        override fun handle(path: String): WebResourceResponse? {
            val response = internalHandler.handle(path) ?: return null
            val headers = response.responseHeaders?.toMutableMap() ?: mutableMapOf()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "*"
            response.responseHeaders = headers
            return response
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        private val CALLBACK_NAME_REGEX =
            Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    }
}
