---

```markdown
# 🎧 VibeAudio (Android)

> **Experience Books Like Never Before.** > A Smart Hybrid Audio Player built with Kotlin & Modern Web Technologies.

![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Status](https://img.shields.io/badge/Status-Production%20Ready-success?style=for-the-badge)

## ⚡ About The Project

**VibeAudio** is not just a standard WebView wrapper. It is a highly optimized **Hybrid Application** that bridges the gap between the Web and Native Android capabilities. 

It creates a seamless audio streaming experience with **Cloud Sync (Clerk Auth)** and a robust **Offline Mode** that bypasses standard WebView security restrictions using a custom local server implementation.

---

## 🚀 Key Features

* **☁️ Cloud Sync:** Seamlessly syncs user progress and library across devices using **Clerk Authentication**.
* **📥 Smart Offline Mode:** Download audiobooks and listen without internet.
    * *Tech Stack:* Uses `WebViewAssetLoader` to serve local files via a virtual secure origin (`https://appassets.androidplatform.net`), solving modern Android's `file://` scheme restrictions.
* **🔐 Secure Persistence:** Implements `CookieManager` sync to ensure login sessions survive app restarts.
* **🎵 Native Media Controls:** Full support for Lock Screen controls and Notification panel (Play/Pause/Next/Prev) using `MediaSessionCompat`.
* **🛠️ Bridge Architecture:** A custom `JavaScriptInterface` enables two-way communication between the Android Native Layer (Kotlin) and the Frontend (Vanilla JS).

---

## 🏗️ Under The Hood (Architecture)

This app solves the complex **CORS and Local File Access** issues found in modern Android WebViews (API 30+).

### 1. The Offline Solution (Virtual Local Server)
Instead of loading unsafe `file:///` URLs, the app intercepts requests and serves downloaded audio files via a virtual HTTPS domain:
```kotlin
// Intercepts [https://appassets.androidplatform.net/local/](https://appassets.androidplatform.net/local/) and serves files from internal storage
assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/local/", CORSPathHandler(this, this.filesDir))
    .build()

```

### 2. The Bridge (JS Interface)

The Web App calls Native Android functions directly:

* `AndroidInterface.downloadFile(url, name)` -> Triggers background download.
* `AndroidInterface.checkFile(name)` -> Checks if file exists on disk.
* `AndroidInterface.updateMediaNotification(...)` -> Syncs native UI with Web Player.

---

## 📸 Screenshots

| Home Library | Player UI | Lock Screen Controls |
| --- | --- | --- |
| *(Add Screenshot Here)* | *(Add Screenshot Here)* | *(Add Screenshot Here)* |

---

## 🛠️ Setup & Installation

1. **Clone the Repo**
```bash
git clone [https://github.com/naveenamre/VibeAudio_android.git](https://github.com/naveenamre/VibeAudio_android.git)

```


2. **Open in Android Studio**
* Wait for Gradle Sync to complete.


3. **Build & Run**
* Connect your device via USB or use an Emulator.
* Click the **Run (▶)** button.



---

## 🤝 Contribution

Feel free to fork this repository and submit pull requests.

* **Frontend Repo:** [https://github.com/naveenamre/VibeAudio.git]
* **Live Site:** [https://vibeaudio.pages.dev](https://vibeaudio.pages.dev)

---

## 👨‍💻 Author

**Naksh** *Code, Vibe, and a lot of Coffee ☕*

---

> *Built with ❤️ using Kotlin, WebView, and Vibe.*

```