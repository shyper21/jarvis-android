package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashView: View
    private lateinit var offlineView: View
    private lateinit var splashLogo: ImageView
    private lateinit var micContainer: View
    private lateinit var micButton: ImageButton
    private lateinit var micLabel: TextView
    private lateinit var tts: TextToSpeech

    private var ttsReady = false
    private var micPulseAnimator: ObjectAnimator? = null

    private val AUDIO_PERMISSION_REQUEST = 1001
    private val APP_URL = "https://shyper-assistant.vercel.app"
    private val RELEASES_API = "https://api.github.com/repos/shyper21/jarvis-android/releases/latest"

    // Exposed to the web app via window.Android.*
    inner class JarvisBridge {
        @JavascriptInterface
        fun speak(text: String) {
            if (ttsReady && text.isNotBlank()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts")
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            if (ttsReady) tts.stop()
        }

        @JavascriptInterface
        fun onListeningStarted() {
            runOnUiThread { setMicState(listening = true) }
        }

        @JavascriptInterface
        fun onListeningEnded() {
            runOnUiThread { setMicState(listening = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        offlineView = findViewById(R.id.offlineView)
        splashLogo = findViewById(R.id.splashLogo)
        micContainer = findViewById(R.id.micContainer)
        micButton = findViewById(R.id.micButton)
        micLabel = findViewById(R.id.micLabel)

        setupTts()
        startSplashLogoAnimation()
        requestMicPermission()

        micButton.setOnClickListener {
            webView.evaluateJavascript(
                "window.jarvisStartListening && window.jarvisStartListening()", null
            )
        }

        if (isOnline()) {
            setupWebView()
            webView.loadUrl(APP_URL)
            checkForUpdates()
        } else {
            showOfflineView()
        }

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                offlineView.visibility = View.GONE
                splashView.visibility = View.VISIBLE
                splashView.alpha = 1f
                setupWebView()
                webView.loadUrl(APP_URL)
            }
        }
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.US
            }
        }
    }

    private fun setMicState(listening: Boolean) {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micButton.scaleX = 1f
        micButton.scaleY = 1f

        if (listening) {
            micLabel.text = getString(R.string.mic_listening)
            micButton.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#a855f7"))
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f)
            micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(micButton, scaleX, scaleY)
                .apply {
                    duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
        } else {
            micLabel.text = getString(R.string.mic_idle)
            micButton.backgroundTintList = null
        }
    }

    private fun startSplashLogoAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f, 1f)
        ObjectAnimator.ofPropertyValuesHolder(splashLogo, scaleX, scaleY).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun dismissSplash() {
        splashView.animate()
            .alpha(0f)
            .setDuration(600)
            .withEndAction { splashView.visibility = View.GONE }
            .start()
    }

    private fun showOfflineView() {
        splashView.visibility = View.GONE
        webView.visibility = View.GONE
        micContainer.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupWebView() {
        webView.addJavascriptInterface(JarvisBridge(), "Android")

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                dismissSplash()
                micContainer.visibility = View.VISIBLE
                view?.evaluateJavascript(buildBridgeJs(), null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    showOfflineView()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }
    }

    /**
     * JavaScript injected after each page load.
     *
     * 1. Overrides window.speechSynthesis so Jarvis responses are spoken via
     *    Android TextToSpeech instead of the silent WebView browser TTS.
     *
     * 2. Wraps SpeechRecognition so auto-start is blocked; recognition only
     *    begins when the native mic button calls window.jarvisStartListening().
     *    Android.onListeningStarted / onListeningEnded keep the button in sync.
     */
    private fun buildBridgeJs(): String = """
(function() {
    'use strict';

    /* ── 1. SPEECH SYNTHESIS → ANDROID TTS ─────────────────────────── */
    window.speechSynthesis = (function() {
        var speaking = false;
        return {
            get speaking() { return speaking; },
            get paused()   { return false; },
            get pending()  { return false; },
            speak: function(utterance) {
                if (!utterance) return;
                speaking = true;
                if (typeof Android !== 'undefined' && utterance.text) {
                    Android.speak(utterance.text);
                }
                if (utterance.onstart) try { utterance.onstart({}); } catch(e) {}
                var words = (utterance.text || '').split(/\s+/).length;
                var ms = Math.max(500, Math.round((words / 150) * 60000));
                setTimeout(function() {
                    speaking = false;
                    if (utterance.onend) try { utterance.onend({}); } catch(e) {}
                }, ms);
            },
            cancel: function() {
                speaking = false;
                if (typeof Android !== 'undefined') Android.stopSpeaking();
            },
            pause:               function() {},
            resume:              function() {},
            getVoices:           function() { return []; },
            addEventListener:    function() {},
            removeEventListener: function() {}
        };
    })();

    /* ── 2. SPEECH RECOGNITION — TAP-TO-ACTIVATE ────────────────────── */
    var NativeSR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!NativeSR) return;

    var _pendingTarget  = null;
    var _listeningActive = false;

    function JarvisSR() {
        var inst = new NativeSR();

        var proxy = new Proxy(inst, {
            get: function(target, prop) {
                if (prop === 'start') {
                    return function() {
                        /* Block auto-start; store instance for native tap */
                        _pendingTarget = target;
                    };
                }
                if (prop === 'stop') {
                    return function() {
                        if (_listeningActive) target.stop();
                    };
                }
                if (prop === 'abort') {
                    return function() {
                        if (_listeningActive) {
                            _listeningActive = false;
                            target.abort();
                            if (typeof Android !== 'undefined') Android.onListeningEnded();
                        }
                    };
                }
                var val = target[prop];
                return typeof val === 'function' ? val.bind(target) : val;
            },
            set: function(target, prop, value) {
                if (prop === 'onend') {
                    target.onend = function(e) {
                        _listeningActive = false;
                        if (typeof Android !== 'undefined') Android.onListeningEnded();
                        if (typeof value === 'function') value.call(target, e);
                    };
                } else if (prop === 'onerror') {
                    target.onerror = function(e) {
                        _listeningActive = false;
                        if (typeof Android !== 'undefined') Android.onListeningEnded();
                        if (typeof value === 'function') value.call(target, e);
                    };
                } else {
                    target[prop] = value;
                }
                return true;
            }
        });

        return proxy;
    }

    window.SpeechRecognition       = JarvisSR;
    window.webkitSpeechRecognition = JarvisSR;

    /* Called by the native mic button tap */
    window.jarvisStartListening = function() {
        if (_pendingTarget && !_listeningActive) {
            _listeningActive = true;
            _pendingTarget.start();
            if (typeof Android !== 'undefined') Android.onListeningStarted();
            return;
        }
        /* Fallback: click the web app's own mic button if found */
        var selectors = [
            'button[aria-label*="mic" i]',
            'button[aria-label*="voice" i]',
            'button[aria-label*="speak" i]',
            'button[aria-label*="listen" i]',
            '[role="button"][aria-label*="mic" i]'
        ];
        for (var i = 0; i < selectors.length; i++) {
            var el = document.querySelector(selectors[i]);
            if (el) { el.click(); return; }
        }
    };

    /* Push page content up so the native button doesn't overlap it */
    if (document.body) {
        document.body.style.paddingBottom = '110px';
    }
})();
""".trimIndent()

    private fun checkForUpdates() {
        Thread {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val latestTag = json.optString("tag_name", "")
                    val latestVersion = latestTag.removePrefix("v").trim()

                    val assets = json.optJSONArray("assets")
                    val downloadUrl = if (assets != null && assets.length() > 0) {
                        assets.getJSONObject(0).optString("browser_download_url", "")
                    } else {
                        json.optString("html_url", "")
                    }

                    val currentVersion = packageManager
                        .getPackageInfo(packageName, 0).versionName ?: "0"

                    if (latestVersion.isNotEmpty() && isNewerVersion(latestVersion, currentVersion)) {
                        runOnUiThread { showUpdateDialog(latestVersion, downloadUrl) }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Update check is non-critical; fail silently
            }
        }.start()
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    private fun showUpdateDialog(version: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("Jarvis v$version is available. Would you like to update now?")
            .setPositiveButton("Update") { _, _ ->
                if (downloadUrl.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("Microphone Access")
                .setMessage("Jarvis needs microphone access to hear your voice commands.")
                .setPositiveButton("Allow") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        AUDIO_PERMISSION_REQUEST
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
