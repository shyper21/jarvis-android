package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var micFab: FloatingActionButton
    private lateinit var micLabel: TextView
    private lateinit var tts: TextToSpeech

    private var ttsReady = false
    private var micPulseAnimator: ObjectAnimator? = null

    private val AUDIO_PERMISSION_REQUEST = 1001
    private val APP_URL = "https://shyper-assistant.vercel.app"
    private val RELEASES_API =
        "https://api.github.com/repos/shyper21/jarvis-android/releases/latest"

    // ── TTS bridge — registered as window.AndroidTTS ──────────────────────────

    inner class JarvisTTSBridge {
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

    // ── App-launcher bridge — registered as window.AndroidLauncher ───────────

    inner class AndroidLauncher {

        @JavascriptInterface
        fun openYouTube(query: String) = runOnUiThread {
            val app = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
            }
            safeStart(app) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                )
            }
        }

        @JavascriptInterface
        fun openCamera() = runOnUiThread {
            safeStart(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) {
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            }
        }

        @JavascriptInterface
        fun openHotspot() = runOnUiThread {
            val tether = Intent().apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
            }
            safeStart(tether) { Intent(Settings.ACTION_WIRELESS_SETTINGS) }
        }

        @JavascriptInterface
        fun openWhatsApp() = runOnUiThread {
            val wa = packageManager.getLaunchIntentForPackage("com.whatsapp")
            safeStart(wa) { Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/")) }
        }

        @JavascriptInterface
        fun openMaps(location: String) = runOnUiThread {
            val maps =
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(location)}")).apply {
                    setPackage("com.google.android.apps.maps")
                }
            safeStart(maps) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://maps.google.com/maps?q=${Uri.encode(location)}")
                )
            }
        }

        @JavascriptInterface
        fun openSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        @JavascriptInterface
        fun openApp(packageName: String) = runOnUiThread {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName) ?: return@runOnUiThread
            safeStart(intent) { null }
        }

        private fun safeStart(primary: Intent?, fallback: () -> Intent?) {
            try {
                if (primary != null) {
                    startActivity(primary)
                    return
                }
            } catch (_: ActivityNotFoundException) {}
            try {
                fallback()?.let { startActivity(it) }
            } catch (_: ActivityNotFoundException) {}
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView      = findViewById(R.id.webView)
        splashView   = findViewById(R.id.splashView)
        offlineView  = findViewById(R.id.offlineView)
        splashLogo   = findViewById(R.id.splashLogo)
        micContainer = findViewById(R.id.micContainer)
        micFab       = findViewById(R.id.micFab)
        micLabel     = findViewById(R.id.micLabel)

        initTts()
        startSplashLogoAnimation()
        requestMicPermission()
        wireMicButton()

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
                splashView.visibility  = View.VISIBLE
                splashView.alpha       = 1f
                setupWebView()
                webView.loadUrl(APP_URL)
            }
        }
    }

    // ── TTS initialisation ────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts.language = Locale.US
        }
    }

    // ── Push-to-talk wiring ───────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun wireMicButton() {
        micFab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    setMicState(listening = true)
                    webView.evaluateJavascript(
                        "window.jarvisStartListening && window.jarvisStartListening()", null
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    webView.evaluateJavascript(
                        "window.jarvisStopListening && window.jarvisStopListening()", null
                    )
                    true
                }
                else -> false
            }
        }
    }

    // ── Mic FAB visual state ──────────────────────────────────────────────────

    private fun setMicState(listening: Boolean) {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micFab.scaleX = 1f
        micFab.scaleY = 1f

        if (listening) {
            micLabel.text = getString(R.string.mic_listening)
            micFab.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#a855f7"))
            val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f)
            val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f)
            micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(micFab, sx, sy).apply {
                duration    = 700
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            micLabel.text = getString(R.string.mic_idle)
            micFab.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#7c3aed"))
        }
    }

    // ── Splash / offline helpers ──────────────────────────────────────────────

    private fun startSplashLogoAnimation() {
        val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f)
        val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f, 1f)
        ObjectAnimator.ofPropertyValuesHolder(splashLogo, sx, sy).apply {
            duration     = 2000
            repeatCount  = ObjectAnimator.INFINITE
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
        splashView.visibility   = View.GONE
        webView.visibility      = View.GONE
        micContainer.visibility = View.GONE
        offlineView.visibility  = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm      = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private fun setupWebView() {
        webView.addJavascriptInterface(JarvisTTSBridge(), "AndroidTTS")
        webView.addJavascriptInterface(AndroidLauncher(), "AndroidLauncher")

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled                = true
        settings.domStorageEnabled                = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess                  = true
        settings.allowContentAccess               = true

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
                if (request?.isForMainFrame == true) showOfflineView()
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
     * JavaScript injected after every page load.
     *
     * 1. Overrides window.speechSynthesis → AndroidTTS.speak() so Jarvis
     *    responses are spoken via Android TextToSpeech (fixes silent WebView TTS).
     *
     * 2. Wraps SpeechRecognition for push-to-talk: auto-start is blocked;
     *    jarvisStartListening() / jarvisStopListening() are called by the native
     *    FAB on hold / release.  AndroidTTS.onListeningStarted/Ended() keep the
     *    button animation in sync.
     *
     * 3. Intercepts fetch() calls to the Groq API and prepends a DuckDuckGo
     *    instant-answer snippet when the user message contains real-time keywords.
     */
    private fun buildBridgeJs(): String = """
(function() {
    'use strict';

    /* ── 1. SPEECH SYNTHESIS → AndroidTTS.speak() ──────────────────── */
    window.speechSynthesis = (function() {
        var speaking = false;
        return {
            get speaking() { return speaking; },
            get paused()   { return false; },
            get pending()  { return false; },
            speak: function(utterance) {
                if (!utterance) return;
                speaking = true;
                if (window.AndroidTTS && utterance.text) {
                    window.AndroidTTS.speak(utterance.text);
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
                if (window.AndroidTTS) window.AndroidTTS.stopSpeaking();
            },
            pause:               function() {},
            resume:              function() {},
            getVoices:           function() { return []; },
            addEventListener:    function() {},
            removeEventListener: function() {}
        };
    })();

    /* ── 2. SPEECH RECOGNITION — PUSH-TO-TALK ──────────────────────── */
    var NativeSR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (NativeSR) {
        var _pendingTarget   = null;
        var _listeningActive = false;

        function JarvisSR() {
            var inst = new NativeSR();
            return new Proxy(inst, {
                get: function(target, prop) {
                    if (prop === 'start') return function() {
                        /* Block web-app auto-start; hold for native button tap */
                        _pendingTarget = target;
                    };
                    if (prop === 'stop') return function() {
                        if (_listeningActive) target.stop();
                    };
                    if (prop === 'abort') return function() {
                        if (_listeningActive) {
                            _listeningActive = false;
                            target.abort();
                            if (window.AndroidTTS) window.AndroidTTS.onListeningEnded();
                        }
                    };
                    var val = target[prop];
                    return typeof val === 'function' ? val.bind(target) : val;
                },
                set: function(target, prop, value) {
                    if (prop === 'onend') {
                        target.onend = function(e) {
                            _listeningActive = false;
                            if (window.AndroidTTS) window.AndroidTTS.onListeningEnded();
                            if (typeof value === 'function') value.call(target, e);
                        };
                    } else if (prop === 'onerror') {
                        target.onerror = function(e) {
                            _listeningActive = false;
                            if (window.AndroidTTS) window.AndroidTTS.onListeningEnded();
                            if (typeof value === 'function') value.call(target, e);
                        };
                    } else {
                        target[prop] = value;
                    }
                    return true;
                }
            });
        }

        window.SpeechRecognition       = JarvisSR;
        window.webkitSpeechRecognition = JarvisSR;

        /* Called by native FAB on hold-down */
        window.jarvisStartListening = function() {
            if (_pendingTarget && !_listeningActive) {
                _listeningActive = true;
                _pendingTarget.start();
                if (window.AndroidTTS) window.AndroidTTS.onListeningStarted();
                return;
            }
            /* Fallback: click the web app's own mic button if discoverable */
            var selectors = [
                'button[aria-label*="mic" i]',
                'button[aria-label*="voice" i]',
                'button[aria-label*="speak" i]',
                '[role="button"][aria-label*="mic" i]'
            ];
            for (var i = 0; i < selectors.length; i++) {
                var el = document.querySelector(selectors[i]);
                if (el) { el.click(); return; }
            }
        };

        /* Called by native FAB on release */
        window.jarvisStopListening = function() {
            if (_pendingTarget && _listeningActive) {
                _pendingTarget.stop();
                /* _listeningActive is cleared when onend fires */
            }
        };
    }

    /* ── 3. REAL-TIME WEB SEARCH ENRICHMENT (DuckDuckGo) ───────────── */
    var RT_KEYWORDS = [
        'harga','price','cuaca','weather','berita','news',
        'today','hari ini','sekarang','now','terkini','latest',
        'current','semalam','yesterday','forex','saham','stock'
    ];

    function needsSearch(text) {
        if (!text) return false;
        var lower = text.toLowerCase();
        for (var i = 0; i < RT_KEYWORDS.length; i++) {
            if (lower.indexOf(RT_KEYWORDS[i]) !== -1) return true;
        }
        return false;
    }

    async function ddgSnippet(query) {
        try {
            var url = 'https://api.duckduckgo.com/?q=' + encodeURIComponent(query) +
                      '&format=json&no_html=1&skip_disambig=1&t=jarvis';
            var res  = await _origFetch(url);
            var data = await res.json();
            var parts = [];
            if (data.Answer)       parts.push(data.Answer);
            if (data.AbstractText) parts.push(data.AbstractText);
            if (data.RelatedTopics) {
                data.RelatedTopics.slice(0, 3).forEach(function(t) {
                    if (t.Text) parts.push(t.Text);
                });
            }
            return parts.join(' | ');
        } catch(e) { return ''; }
    }

    var _origFetch = window.fetch;
    window.fetch = async function(url, options) {
        var urlStr = typeof url === 'string' ? url : (url && url.url ? url.url : '');
        if (urlStr.indexOf('groq.com') === -1) {
            return _origFetch(url, options);
        }
        try {
            var body = options && options.body ? JSON.parse(options.body) : null;
            if (body && body.messages) {
                var lastUserIdx = -1;
                for (var i = body.messages.length - 1; i >= 0; i--) {
                    if (body.messages[i].role === 'user') { lastUserIdx = i; break; }
                }
                if (lastUserIdx !== -1 && needsSearch(body.messages[lastUserIdx].content)) {
                    var snippet = await ddgSnippet(body.messages[lastUserIdx].content);
                    if (snippet) {
                        var msgs = body.messages.slice();
                        msgs.splice(lastUserIdx, 0, {
                            role:    'system',
                            content: '[Web search context: ' + snippet + ']'
                        });
                        body.messages = msgs;
                        options = Object.assign({}, options, { body: JSON.stringify(body) });
                    }
                }
            }
        } catch(e) {}
        return _origFetch(url, options);
    };

    /* Push page content up so the FAB does not overlap chat input */
    if (document.body) document.body.style.paddingBottom = '110px';
})();
""".trimIndent()

    // ── Auto-update ───────────────────────────────────────────────────────────

    private fun checkForUpdates() {
        Thread {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 6000
                conn.readTimeout    = 6000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body          = conn.inputStream.bufferedReader().readText()
                    val json          = JSONObject(body)
                    val latestTag     = json.optString("tag_name", "")
                    val latestVersion = latestTag.removePrefix("v").trim()
                    val assets        = json.optJSONArray("assets")
                    val downloadUrl   = if (assets != null && assets.length() > 0) {
                        assets.getJSONObject(0).optString("browser_download_url", "")
                    } else {
                        json.optString("html_url", "")
                    }
                    val currentVersion =
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    if (latestVersion.isNotEmpty() && isNewerVersion(latestVersion, currentVersion)) {
                        runOnUiThread { showUpdateDialog(latestVersion, downloadUrl) }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { /* non-critical */ }
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
                if (downloadUrl.isNotEmpty())
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) return

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO
            )
        ) {
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

    // ── Navigation / lifecycle ────────────────────────────────────────────────

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
