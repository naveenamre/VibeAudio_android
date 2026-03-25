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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.URLUtil
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

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastPlaybackDispatchKey: String? = null
    private var didRecoverRenderer = false

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            PlaybackBridge.detach(webView)
            destroyWebView()
        }
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
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
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
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString VibeAudioAndroid/1.1"
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            queueDownload(url, guessedFileName, userAgent, null)
        }

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
                lastPlaybackDispatchKey = null
                CookieManager.getInstance().flush()
                injectAndroidPlaybackSupport()
                webView.evaluateJavascript("document.body && document.body.classList.add('is-android');", null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.isVisible = false
                    Log.e(
                        "VibeWeb",
                        "Main frame load error ${error?.errorCode}: ${error?.description}"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    Log.e(
                        "VibeWeb",
                        "HTTP ${errorResponse?.statusCode} while loading ${request.url}"
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val rendererInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    "didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}"
                } else {
                    "details_unavailable"
                }
                Log.e(
                    "VibeWeb",
                    "WebView renderer gone. $rendererInfo"
                )
                progressBar.isVisible = false
                recoverWebViewAfterRendererExit()
                return true
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
            if (::webView.isInitialized) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun dispatchPlaybackState(title: String, artist: String, artworkUrl: String, isPlaying: Boolean) {
        val safeTitle = title.ifBlank { getString(R.string.app_name) }
        val safeArtist = artist.ifBlank { "Web Player" }
        val dispatchKey = listOf(safeTitle, safeArtist, artworkUrl, isPlaying).joinToString("|")

        if (!isPlaying && !MediaPlaybackService.isRunning()) {
            return
        }

        if (dispatchKey == lastPlaybackDispatchKey && MediaPlaybackService.isRunning()) {
            return
        }

        val playbackIntent = MediaPlaybackService.buildUpdateIntent(
            context = this,
            title = safeTitle,
            artist = safeArtist,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying
        )

        lastPlaybackDispatchKey = dispatchKey
        runOnUiThread {
            runCatching {
                if (isPlaying) {
                    ContextCompat.startForegroundService(this, playbackIntent)
                } else {
                    startService(playbackIntent)
                }
            }.onFailure { error ->
                lastPlaybackDispatchKey = null
                Log.e("VibePlayback", "Playback state dispatch failed", error)
            }
        }
    }

    private fun queueDownload(
        url: String,
        fileName: String,
        userAgentOverride: String?,
        callbackName: String?
    ) {
        val safeFileName = fileName.ifBlank { "download-${System.currentTimeMillis()}" }
        val referer = webView.url.orEmpty()
        val userAgent = userAgentOverride ?: webView.settings.userAgentString

        when {
            url.startsWith("data:", ignoreCase = true) -> {
                val payload = extractBase64Payload(url)
                bridgeExecutor.execute {
                    val result = if (payload.isBlank()) {
                        Result.failure<String>(IllegalArgumentException("Data URL payload empty hai"))
                    } else {
                        OfflineAudioStore.saveBase64(
                            context = this,
                            base64Payload = payload,
                            requestedFileName = safeFileName
                        )
                    }
                    deliverDownloadResult(callbackName, result)
                }
            }

            url.startsWith("blob:", ignoreCase = true) -> downloadBlobFromWebView(url, safeFileName, callbackName)

            else -> bridgeExecutor.execute {
                val result = OfflineAudioStore.download(
                    context = this,
                    sourceUrl = url,
                    requestedFileName = safeFileName,
                    userAgent = userAgent,
                    referer = referer
                )
                deliverDownloadResult(callbackName, result)
            }
        }
    }

    private fun deliverDownloadResult(callbackName: String?, result: Result<String>) {
        val payload = result.getOrNull() ?: (result.exceptionOrNull()?.message ?: "")
        if (!callbackName.isNullOrBlank()) {
            postJavascriptCallback(callbackName, result.isSuccess, payload)
            return
        }

        if (result.isSuccess) {
            Log.i("VibeDownload", "Download completed: $payload")
        } else {
            Log.e("VibeDownload", "Download failed: $payload")
        }
    }

    private fun extractBase64Payload(dataUrl: String): String {
        val commaIndex = dataUrl.indexOf(',')
        return if (commaIndex >= 0 && commaIndex < dataUrl.lastIndex) {
            dataUrl.substring(commaIndex + 1)
        } else {
            ""
        }
    }

    private fun downloadBlobFromWebView(url: String, fileName: String, callbackName: String?) {
        val safeCallbackName = callbackName.orEmpty()
        val script = """
            (() => {
              const blobUrl = ${JSONObject.quote(url)};
              const fileName = ${JSONObject.quote(fileName)};
              const callbackName = ${JSONObject.quote(safeCallbackName)};
              const resolveCallback = (path) => {
                if (!path) return null;
                return path.split(".").reduce((obj, key) => (obj ? obj[key] : null), window);
              };
              fetch(blobUrl)
                .then((response) => response.blob())
                .then((blob) => new Promise((resolve, reject) => {
                  const reader = new FileReader();
                  reader.onload = () => resolve(String(reader.result || ""));
                  reader.onerror = () => reject(reader.error || new Error("blob-read-failed"));
                  reader.readAsDataURL(blob);
                }))
                .then((dataUrl) => {
                  const commaIndex = dataUrl.indexOf(",");
                  const payload = commaIndex >= 0 ? dataUrl.slice(commaIndex + 1) : "";
                  if (!payload) {
                    throw new Error("Blob payload empty hai");
                  }
                  AndroidInterface.saveBase64File(payload, fileName, callbackName);
                })
                .catch((error) => {
                  const callback = resolveCallback(callbackName);
                  if (typeof callback === "function") {
                    callback(false, error && error.message ? error.message : "Blob download failed");
                  }
                });
              return true;
            })();
        """.trimIndent()

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun recoverWebViewAfterRendererExit() {
        if (didRecoverRenderer || isFinishing || isDestroyed) {
            finish()
            return
        }

        didRecoverRenderer = true
        runOnUiThread {
            destroyWebView()

            val parent = findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as? ViewGroup
            val newWebView = WebView(this).apply {
                id = R.id.webView
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            parent?.addView(newWebView, 0)
            webView = newWebView
            PlaybackBridge.attach(webView)
            configureWebView()
            webView.loadUrl(appUrl)
        }
    }

    private fun destroyWebView() {
        runCatching {
            if (customView != null) {
                hideCustomView()
            }
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface("AndroidInterface")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }.onFailure {
            Log.w("VibeWeb", "WebView destroy failed", it)
        }
    }

    private inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun checkFile(fileName: String): String {
            return OfflineAudioStore.resolveVirtualUrl(context, fileName).orEmpty()
        }

        @JavascriptInterface
        fun downloadFile(urlStr: String, fileName: String, callbackName: String) {
            queueDownload(urlStr, fileName, null, callbackName)
        }

        @JavascriptInterface
        fun deleteFile(fileName: String) {
            OfflineAudioStore.delete(context, fileName)
        }

        @JavascriptInterface
        fun saveBase64File(base64Payload: String, fileName: String, callbackName: String) {
            bridgeExecutor.execute {
                val result = OfflineAudioStore.saveBase64(
                    context = context,
                    base64Payload = base64Payload,
                    requestedFileName = fileName
                )
                postJavascriptCallback(
                    callbackName = callbackName,
                    success = result.isSuccess,
                    payload = result.getOrNull() ?: (result.exceptionOrNull()?.message ?: "")
                )
            }
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
        private val bridgeExecutor: ExecutorService = Executors.newFixedThreadPool(2)
        private val CALLBACK_NAME_REGEX =
            Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    }
}
