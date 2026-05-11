# 🤖 Jarvis AI Assistant

<p align="center">
  <img src="app/src/main/res/drawable/ic_jarvis.xml" width="100" alt="Jarvis Logo"/>
</p>

<p align="center">
  <a href="https://github.com/shyper21/jarvis-android/releases/latest">
    <img src="https://img.shields.io/github/v/release/shyper21/jarvis-android?label=Download&color=4CAF50" alt="Latest Release"/>
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-blue?logo=android" alt="Android 8.0+"/>
  <img src="https://img.shields.io/badge/Kotlin-1.x-purple?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Build-Passing-brightgreen" alt="Build Status"/>
</p>

**Jarvis** adalah aplikasi asisten AI untuk Android yang didukung oleh web app berbasis Claude AI. Cukup bicara, Jarvis mendengarkan — dan bisa langsung membuka YouTube, Maps, WhatsApp, Kamera, dan lainnya langsung dari perintah suara kamu.

---

## ✨ Fitur

- 🎙️ **Input Suara** — Tombol mic untuk mulai/berhenti mendengarkan, dengan timeout otomatis 10 detik
- 🌐 **AI Berbasis Web** — Powered by [shyper-assistant.vercel.app](https://shyper-assistant.vercel.app) (Claude AI)
- 📱 **App Launcher** — Buka aplikasi langsung lewat perintah suara:
  - YouTube (cari video)
  - Google Maps (navigasi lokasi)
  - WhatsApp
  - Kamera
  - Pengaturan sistem
- 🔄 **Auto-Update** — Cek dan download versi terbaru langsung dari GitHub Releases
- 📶 **Offline Detection** — Tampil halaman offline + tombol Retry saat tidak ada koneksi
- 🚀 **Splash Screen** — Animasi logo halus saat pertama kali dibuka
- 📍 **Geolokasi** — Mendukung permintaan lokasi dari web

---

## 📸 Screenshot

> *(Tambahkan screenshot aplikasi kamu di sini)*

---

## 📥 Download & Install

1. Buka halaman [**Releases**](https://github.com/shyper21/jarvis-android/releases/latest)
2. Download file `Jarvis-vX.X.apk`
3. Aktifkan **"Install from Unknown Sources"** di pengaturan Android kamu
4. Install APK dan buka Jarvis!

---

## 🛠️ Build dari Source

### Prasyarat

- Android Studio Hedgehog atau lebih baru
- JDK 17
- Android SDK (API 26+)

### Langkah Build

```bash
# Clone repo
git clone https://github.com/shyper21/jarvis-android.git
cd jarvis-android

# Build debug APK
./gradlew assembleDebug
```

APK akan tersedia di:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚙️ CI/CD — GitHub Actions

Setiap push ke branch `main` akan otomatis:

1. Build APK debug
2. Rename APK menjadi `Jarvis-vX.X.apk`
3. Buat atau update **GitHub Release** dengan APK terbaru

Tidak perlu build manual — cukup push, APK langsung tersedia di Releases.

---

## 📋 Permissions

| Permission | Kegunaan |
|---|---|
| `INTERNET` | Memuat web app AI |
| `RECORD_AUDIO` | Input suara / perintah mic |
| `ACCESS_NETWORK_STATE` | Deteksi koneksi internet |

---

## 🧰 Tech Stack

| Komponen | Teknologi |
|---|---|
| Bahasa | Kotlin |
| UI | XML Layout + Material Components |
| WebView | Android WebView + JavascriptInterface |
| HTTP | HttpURLConnection |
| CI/CD | GitHub Actions |
| Backend AI | Vercel (Claude AI) |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 14 (API 34) |

---

## 📁 Struktur Proyek

```
jarvis-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/jarvis/assistant/
│   │   │   └── MainActivity.kt       # Logic utama aplikasi
│   │   ├── res/
│   │   │   ├── drawable/             # Ikon & aset
│   │   │   ├── layout/               # Layout XML
│   │   │   └── values/               # String, tema, warna
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── .github/
│   └── workflows/
│       └── build.yml                 # CI/CD pipeline
└── build.gradle
```

---

## 🤝 Kontribusi

Pull request sangat disambut! Untuk perubahan besar, buka issue terlebih dahulu untuk mendiskusikan apa yang ingin kamu ubah.

1. Fork repo ini
2. Buat branch fitur: `git checkout -b feature/nama-fitur`
3. Commit perubahan: `git commit -m 'Add: nama fitur'`
4. Push ke branch: `git push origin feature/nama-fitur`
5. Buka Pull Request

---

## 📄 Lisensi

Proyek ini menggunakan lisensi [MIT](LICENSE).

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/shyper21">shyper21</a>
</p>
