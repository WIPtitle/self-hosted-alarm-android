package com.wiptitle.ntfy_webapp_android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.network.NtfyConfigFetcher
import com.wiptitle.ntfy_webapp_android.service.NtfyService
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var prefsManager: PreferencesManager
    private lateinit var ntfyConfigFetcher: NtfyConfigFetcher
    private var urlDialog: AlertDialog? = null
    private var isInitialLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupStatusBar()
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(this)
        ntfyConfigFetcher = NtfyConfigFetcher()

        setupWebView()
        setupBackPressHandler()
        requestNotificationPermission()

        val fromNotification = intent.getBooleanExtra("from_notification", false)

        if (intent.getBooleanExtra("ntfy_error", false)) {
            handleNtfyDisconnection()
        } else {
            val savedUrl = prefsManager.webAppUrl
            if (savedUrl.isNullOrEmpty()) {
                showUrlInputDialog()
            } else {
                if (fromNotification) {
                    val notificationsUrl = savedUrl.trimEnd('/') + "/ui/notifications"
                    loadWebApp(notificationsUrl)
                } else {
                    loadWebApp(savedUrl)
                }
                if (isInitialLoad && !isServiceConnected()) {
                    fetchNtfyConfigAndConnect(savedUrl)
                }
            }
        }
        isInitialLoad = false
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent?.getBooleanExtra("from_notification", false) == true) {
            val savedUrl = prefsManager.webAppUrl
            if (!savedUrl.isNullOrEmpty()) {
                val notificationsUrl = savedUrl.trimEnd('/') + "/ui/notifications"
                loadWebApp(notificationsUrl)
            }
        }
    }

    private fun setupStatusBar() {
        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.statusBarColor = ContextCompat.getColor(this, R.color.webapp_background)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.webapp_background)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = ContextCompat.getColor(this, R.color.webapp_background)
        }
    }

    private fun isServiceConnected(): Boolean {
        return prefsManager.isNtfyConnected
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowUniversalAccessFromFileURLs = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)

                if (request?.isForMainFrame == true) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    if (statusCode in 400..599) {
                        runOnUiThread {
                            handleWebAppError("WebApp returned error: $statusCode")
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        handleWebAppError("Failed to load WebApp")
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }

        webView.webChromeClient = WebChromeClient()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.loadUrl("about:blank")
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showUrlInputDialog() {
        if (urlDialog?.isShowing == true) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_url_input, null)
        val urlInput = dialogView.findViewById<TextInputEditText>(R.id.url_input)

        prefsManager.webAppUrl?.let { urlInput.setText(it) }

        urlDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Connect") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    processUrl(url)
                } else {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                    showUrlInputDialog()
                }
            }
            .create()

        urlDialog?.show()
    }

    private fun processUrl(url: String) {
        // Stop service only if it's configured
        if (prefsManager.isNtfyConfigured()) {
            stopNtfyService()
        }

        prefsManager.clearNtfyConfig()
        prefsManager.webAppUrl = url

        loadWebApp(url)
        fetchNtfyConfigAndConnect(url)
    }

    private fun loadWebApp(url: String) {
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearSslPreferences()
        webView.loadUrl(url)
    }

    fun fetchNtfyConfigAndConnect(webAppUrl: String) {
        ntfyConfigFetcher.fetchConfig(webAppUrl) { config ->
            runOnUiThread {
                if (config != null) {
                    try {
                        val url = URL(webAppUrl)
                        val ntfyUrl = "${url.protocol}://${url.authority}"

                        prefsManager.ntfyUrl = ntfyUrl
                        prefsManager.ntfyTopic = config.topic
                        prefsManager.ntfyUsername = config.user
                        prefsManager.ntfyPassword = config.password

                        // Small delay to ensure preferences are written
                        Handler(Looper.getMainLooper()).postDelayed({
                            startNtfyService()
                        }, 100)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to configure notifications", Toast.LENGTH_SHORT).show()
                        e.printStackTrace()
                    }
                } else {
                    Toast.makeText(this, "Failed to fetch notification config", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleWebAppError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        webView.loadUrl("about:blank")
        stopNtfyService()
        prefsManager.clearNtfyConfig()
        showUrlInputDialog()
    }

    fun handleNtfyDisconnection() {
        runOnUiThread {
            val savedUrl = prefsManager.webAppUrl
            if (!savedUrl.isNullOrEmpty()) {
                Toast.makeText(this, "Reconnecting notification service...", Toast.LENGTH_SHORT).show()
                fetchNtfyConfigAndConnect(savedUrl)
            } else {
                showUrlInputDialog()
            }
        }
    }

    private fun stopNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_STOP
        startService(intent)
    }

    private fun startNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_START
        ContextCompat.startForegroundService(this, intent)
    }

    private fun restartNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_RESTART
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}