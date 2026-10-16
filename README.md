<div align="center">
  <img src="assets/logo.png" alt="ReClip Logo" width="128" height="128" style="border-radius: 24px;">

  # ReClip — Desktop & Android Media Downloader

  **A sleek, native media downloading application for Windows and Android.**  
  Download videos and audio from YouTube, TikTok, Instagram, Twitter/X, and 1000+ other sites in MP4 or MP3 format with real-time progress indicators, file size estimation, and system gallery integration.

  <img src="assets/screenshot.png" alt="ReClip Screenshot" width="800">

  ![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Android%207.0%2B-red)
  ![Engine](https://img.shields.io/badge/engine-yt--dlp%20%2B%20ffmpeg-orange)
  ![UI](https://img.shields.io/badge/ui-Motion%20Primitives%20%2B%20Tailwind-blue)
  ![License](https://img.shields.io/badge/license-MIT-green)
</div>

---

## ✨ Features

- **📱 Native Android Application**: Powered by `youtubedl-android`, embedded FFmpeg, and an Anti-Slop UI built with **Motion Primitives (`ibelick/motion-primitives`)**, Framer Motion, and Tailwind CSS.
- **🚀 Native Windows Desktop Application**: Embedded Microsoft Edge WebView2 GUI window—no browser tabs or `http://localhost:8899` server URLs required.
- **⚡ Windowless & Silent Operations**: Zero command prompt or terminal windows popping up during fetching or downloading.
- **📊 Real-Time Progress Bar & File Size Estimation**: View percentage progress bar, live download speeds (`MiB/s`), ETAs, and estimated/final file sizes.
- **📲 Android System Share Sheet Integration**: Tap "Share" on any video in YouTube, Twitter/X, Instagram, TikTok, or Reddit and select **ReClip** to fetch and download instantly without copy-pasting.
- **🔔 Resilient Android Foreground Service**: Background downloading service with persistent notification bar progress; downloads continue uninterrupted even when screen is locked.
- **📁 Automatic MediaStore Registration**: Saved media directly indexes into Android's public `Downloads/ReClip` directory, showing up immediately in VLC, Google Photos, and Music players.
- **🍪 Built-In Cookie Bypass (`cookies.txt`)**: Import or paste `cookies.txt` to bypass YouTube bot detection, age-restrictions, and Chrome DPAPI hurdles.
- **🎵 MP4 Video & MP3 Audio Extraction**: Easily choose between video (1080p, 720p, 480p, 360p) and audio extraction with multi-language dub track support.
- **🌐 1000+ Supported Platforms**: YouTube, TikTok, Instagram, Twitter/X, Reddit, Facebook, Vimeo, Twitch, SoundCloud, Pinterest, Loom, Threads, and many more.

---

## 💾 Downloads & Installation

### Android (.apk)
1. Download **[`ReClip_v1.0.0_Android.apk`](https://github.com/Fazzbro/Reclip/releases)** from GitHub Releases.
2. Sideload and install on any Android device running Android 7.0 (API 24) or newer.

```bash
adb install ReClip_v1.0.0_Android.apk
```

### Windows Desktop (.exe)
1. Download **`ReClip_v1.2.0_Setup.exe`** from [GitHub Releases](https://github.com/Fazzbro/Reclip/releases/tag/v1.2.0) and run the installer.
2. Alternatively, launch `dist/ReClip/ReClip.exe` directly.

---

## 🍪 Setup Guide: YouTube & Chrome DPAPI Bypass

Google Chrome 127+ introduced **App-Bound Encryption**, preventing third-party apps like ReClip from automatically extracting cookies directly from the browser while it's running. This leads to `ERROR: Could not copy Chrome cookie database` or `Failed to decrypt with DPAPI` when downloading YouTube videos.

**To bypass this permanently:**
1. Open Google Chrome.
2. Go to `chrome://extensions/` and enable **Developer Mode**.
3. Click **Load unpacked** and select the `chrome_extension` folder found inside this repository.
4. Click the newly installed **ReClip Cookie Exporter** extension icon and click **"Export cookies.txt"**.
5. Save or import the `cookies.txt` file directly into ReClip (via the **Cookies Tab** in Android or the **Downloads Folder** on Windows).
6. Fetch your video! ReClip will automatically detect the cookies and bypass security blocks.

---

## 🛠️ Building from Source

### Building Android APK
```bash
# 1. Compile the Motion-Primitives React frontend into assets
cd frontend
npm install
npm run build

# 2. Compile and package the Android APK
cd ../android
./gradlew assembleDebug
```
*Output APK located in `android/app/build/outputs/apk/debug/app-debug.apk`.*

### Building Windows Executable
```powershell
python build_windows_app.py
```
*Compiles `ReClip.exe` with the red "R" icon into `dist/ReClip/`.*

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
