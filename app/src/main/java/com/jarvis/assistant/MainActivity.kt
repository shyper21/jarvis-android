package com.jarvis.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val APP_URL = "https://shyper-assistant.vercel.app"
    }

    private lateinit var webView: WebView
    private lateinit var offlineView: LinearLayout
    private lateinit var offlineText: TextView
    private lateinit var retryButton: Button
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) webView.reload() }

    // ══════════════════════════════════════════════════════════════════════════
    //  AndroidLauncher — jembatan JS → native Android
    // ══════════════════════════════════════════════════════════════════════════
    inner class AndroidLauncher {

        @JavascriptInterface
        fun openYouTube(query: String) = runOnUiThread {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
            }
            if (searchIntent.resolveActivity(packageManager) != null) {
                startActivity(searchIntent)
            } else {
                openUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            }
        }

        @JavascriptInterface
        fun openCamera() = runOnUiThread {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        }

        @JavascriptInterface
        fun openWhatsApp() = runOnUiThread {
            startActivity(
                packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
            )
        }

        @JavascriptInterface
        fun openMaps(location: String) = runOnUiThread {
            val mapsIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=${Uri.encode(location)}")
            ).setPackage("com.google.android.apps.maps")

            if (mapsIntent.resolveActivity(packageManager) != null) {
                startActivity(mapsIntent)
            } else {
                openUrl("https://maps.google.com/?q=${Uri.encode(location)}")
            }
        }

        @JavascriptInterface
        fun openSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AndroidTTS — TTS native Android (lebih natural dari browser)
    // ══════════════════════════════════════════════════════════════════════════
    inner class AndroidTTS {

        @JavascriptInterface
        fun speak(text: String) = runOnUiThread {
            if (!ttsReady) return@runOnUiThread
            webView.evaluateJavascript("window.speechSynthesis?.cancel()", null)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }

        @JavascriptInterface
        fun stop() = runOnUiThread { if (ttsReady) tts.stop() }

        @JavascriptInterface
        fun isSpeaking(): Boolean = ttsReady && tts.isSpeaking
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        tts = TextToSpeech(this, this)
        setupWebView()
        if (isOnline()) loadApp() else showOffline()
    }

    override fun onDestroy() {
        tts.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH)
            }
            tts.setSpeechRate(0.95f)
            tts.setPitch(0.85f)
            ttsReady = true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WebView setup
    // ══════════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            setSupportZoom(false)
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Inject bridges ke JS
        webView.addJavascriptInterface(AndroidLauncher(), "AndroidLauncher")
        webView.addJavascriptInterface(AndroidTTS(), "AndroidTTS")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Override browser speechSynthesis → pakai AndroidTTS native
                view.evaluateJavascript("""
                    (function() {
                        if (window.AndroidTTS && window.SpeechSynthesisUtterance) {
                            window.speechSynthesis.speak = function(utt) {
                                AndroidTTS.speak(utt.text || '');
                            };
                        }
                        console.log('[Jarvis] Android bridge ready');
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Izinkan mikrofon dari WebView
            override fun onPermissionRequest(request: PermissionRequest) {
                val toGrant = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    else { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); request.deny(); return }
                }
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources)
                    toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (toGrant.isNotEmpty()) request.grant(toGrant.toTypedArray()) else request.deny()
            }

            // Izinkan geolokasi
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) = callback.invoke(origin, true, false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════
    private fun loadApp() {
        offlineView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(APP_URL)
    }

    private fun showOffline() = runOnUiThread {
        webView.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout (programmatic — tidak perlu ubah XML yang sudah ada)
    // ══════════════════════════════════════════════════════════════════════════
    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF05030F.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        offlineView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xFF05030F.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        offlineText = TextView(this).apply {
            text = "⚡ Tidak ada koneksi internet"
            setTextColor(0xFFC77DFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 24)
        }

        retryButton = Button(this).apply {
            text = "Coba Lagi"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(64, 24, 64, 24)
            setOnClickListener {
                if (isOnline()) loadApp()
                else {
                    offlineText.text = "Masih tidak ada koneksi..."
                    Handler(Looper.getMainLooper()).postDelayed(
                        { offlineText.text = "⚡ Tidak ada koneksi internet" }, 2000
                    )
                }
            }
        }

        offlineView.addView(offlineText)
        offlineView.addView(retryButton)
        root.addView(webView)
        root.addView(offlineView)
        setContentView(root)
    }
}
