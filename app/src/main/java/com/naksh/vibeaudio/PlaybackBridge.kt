package com.naksh.vibeaudio

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference

enum class PlaybackCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS
}

object PlaybackBridge {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webViewRef: WeakReference<WebView>? = null

    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun detach(webView: WebView) {
        val attachedWebView = webViewRef?.get() ?: return
        if (attachedWebView == webView) {
            webViewRef?.clear()
            webViewRef = null
        }
    }

    fun dispatch(command: PlaybackCommand) {
        val webView = webViewRef?.get() ?: return
        val script = when (command) {
            PlaybackCommand.PLAY -> PLAY_SCRIPT
            PlaybackCommand.PAUSE -> PAUSE_SCRIPT
            PlaybackCommand.TOGGLE -> TOGGLE_SCRIPT
            PlaybackCommand.NEXT -> NEXT_SCRIPT
            PlaybackCommand.PREVIOUS -> PREVIOUS_SCRIPT
        }

        mainHandler.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private val PLAY_SCRIPT = """
        (() => {
          try {
            const app = window.app;
            const media = Array.from(document.querySelectorAll("video, audio")).find((item) => item.paused) || document.querySelector("video, audio");
            if (app && typeof app.play === "function") {
              app.play();
              return true;
            }
            if (app && typeof app.togglePlay === "function" && media && media.paused) {
              app.togglePlay();
              return true;
            }
            if (media && media.paused) {
              const playPromise = media.play();
              if (playPromise && typeof playPromise.catch === "function") {
                playPromise.catch(() => {});
              }
              return true;
            }
            const button = document.querySelector(".ytp-play-button, .ytp-large-play-button, [aria-label*='Play']");
            if (button) {
              button.click();
              return true;
            }
          } catch (_) {}
          return false;
        })();
    """.trimIndent()

    private val PAUSE_SCRIPT = """
        (() => {
          try {
            const app = window.app;
            const media = Array.from(document.querySelectorAll("video, audio")).find((item) => !item.paused) || document.querySelector("video, audio");
            if (app && typeof app.pause === "function") {
              app.pause();
              return true;
            }
            if (app && typeof app.togglePlay === "function" && media && !media.paused) {
              app.togglePlay();
              return true;
            }
            if (media && !media.paused) {
              media.pause();
              return true;
            }
            const button = document.querySelector(".ytp-play-button[aria-label*='Pause'], [aria-label*='Pause']");
            if (button) {
              button.click();
              return true;
            }
          } catch (_) {}
          return false;
        })();
    """.trimIndent()

    private val TOGGLE_SCRIPT = """
        (() => {
          try {
            const app = window.app;
            const media = Array.from(document.querySelectorAll("video, audio")).find((item) => !item.paused) || document.querySelector("video, audio");
            if (app && typeof app.togglePlay === "function") {
              app.togglePlay();
              return true;
            }
            if (media) {
              if (media.paused) {
                const playPromise = media.play();
                if (playPromise && typeof playPromise.catch === "function") {
                  playPromise.catch(() => {});
                }
              } else {
                media.pause();
              }
              return true;
            }
            const button = document.querySelector(".ytp-play-button, .ytp-large-play-button, [aria-label*='Play'], [aria-label*='Pause']");
            if (button) {
              button.click();
              return true;
            }
          } catch (_) {}
          return false;
        })();
    """.trimIndent()

    private val NEXT_SCRIPT = """
        (() => {
          try {
            const app = window.app;
            if (app && typeof app.nextChapter === "function") {
              app.nextChapter();
              return true;
            }

            const selectors = [
              "[data-action='next']",
              "[aria-label*='Next']",
              ".ytp-next-button"
            ];

            for (const selector of selectors) {
              const button = document.querySelector(selector);
              if (button) {
                button.click();
                return true;
              }
            }
          } catch (_) {}
          return false;
        })();
    """.trimIndent()

    private val PREVIOUS_SCRIPT = """
        (() => {
          try {
            const app = window.app;
            if (app && typeof app.prevChapter === "function") {
              app.prevChapter();
              return true;
            }

            const media = Array.from(document.querySelectorAll("video, audio")).find((item) => !item.paused) || document.querySelector("video, audio");
            if (media && media.currentTime > 5) {
              media.currentTime = 0;
              return true;
            }

            const selectors = [
              "[data-action='previous']",
              "[data-action='prev']",
              "[aria-label*='Previous']",
              ".ytp-prev-button"
            ];

            for (const selector of selectors) {
              const button = document.querySelector(selector);
              if (button) {
                button.click();
                return true;
              }
            }
          } catch (_) {}
          return false;
        })();
    """.trimIndent()
}
