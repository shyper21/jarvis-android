package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashView: View
    private lateinit var offlineView: View
    private lateinit var splashLogo: ImageView

    private val AUDIO_PERMISSION_REQUEST = 1001
    private val APP_URL = "https://shyper-assistant.vercel.app"
    private val RELEASES_API = "https://api.github.com/repos/shyper21/jarvis-android/releases/latest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        offlineView = findViewById(R.id.offlineView)
        splashLogo = findViewById(R.id.splashLogo)

        startSplashLogoAnimation()
        requestMicPermission()

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
        offlineView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupWebView() {
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
}
