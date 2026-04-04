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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.network.FirebaseConfigChecker
import com.wiptitle.ntfy_webapp_android.network.FirebaseTokenRegistrar
import com.wiptitle.ntfy_webapp_android.network.NtfyConfigFetcher
import com.wiptitle.ntfy_webapp_android.service.SubscriberService
import com.wiptitle.ntfy_webapp_android.service.SubscriberServiceManager
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var prefsManager: PreferencesManager
    private lateinit var ntfyConfigFetcher: NtfyConfigFetcher
    private val firebaseConfigChecker = FirebaseConfigChecker()
    private val firebaseTokenRegistrar = FirebaseTokenRegistrar()
    private var urlDialog: AlertDialog? = null
    private var isInitialLoad = true

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate started")

        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        WebView.clearClientCertPreferences(null)

        setupStatusBar()
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(this)
        ntfyConfigFetcher = NtfyConfigFetcher()

        schedulePeriodicWorker()

        setupWebView()
        setupBackPressHandler()
        requestNotificationPermission()

        val fromNotification = intent.getBooleanExtra("from_notification", false)
        val savedUrl = prefsManager.webAppUrl

        Handler(Looper.getMainLooper()).postDelayed({
            if (intent.getBooleanExtra("ntfy_error", false)) {
                handleNtfyDisconnection()
            } else {
                if (savedUrl.isNullOrEmpty()) {
                    showUrlInputDialog()
                } else {
                    if (fromNotification) {
                        val notificationsUrl = savedUrl.trimEnd('/') + "/ui/notifications"
                        loadWebApp(notificationsUrl)
                    } else {
                        loadWebApp(savedUrl)
                    }
                    if (isInitialLoad && !prefsManager.isNtfyConnected) {
                        initNotifications(savedUrl)
                    }
                }
            }
            isInitialLoad = false
        }, 500)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        SubscriberServiceManager.refresh(this)
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

    private fun schedulePeriodicWorker() {
        if (prefsManager.isFirebaseMode) return
        val workerVersion = prefsManager.getAutoRestartWorkerVersion()
        val workPolicy = if (workerVersion == SubscriberService.SERVICE_START_WORKER_VERSION) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            prefsManager.setAutoRestartWorkerVersion(SubscriberService.SERVICE_START_WORKER_VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<SubscriberServiceManager.ServiceStartWorker>(
            repeatInterval = 6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC)
            .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC,
            workPolicy,
            work
        )
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowUniversalAccessFromFileURLs = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }

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
                        view?.clearSslPreferences()
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

                view?.clearSslPreferences()

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

            override fun onReceivedClientCertRequest(
                view: WebView?,
                request: ClientCertRequest
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.proceed(null, null)
                } else {
                    request.cancel()
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            CookieManager.getInstance().flush()
        }

        webView.loadUrl("about:blank")
    }

    private fun clearWebViewData() {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearSslPreferences()
            webView.clearFormData()

            val cookieManager = CookieManager.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } else {
                cookieManager.removeAllCookie()
                cookieManager.removeSessionCookie()
            }

            webView.clearMatches()
            WebStorage.getInstance().deleteAllData()

            deleteDatabase("webview.db")
            deleteDatabase("webviewCache.db")

        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        prefsManager.clearNtfyConfig()
        prefsManager.webAppUrl = url

        loadWebApp(url)
        initNotifications(url)
    }

    private fun loadWebApp(url: String) {
        clearWebViewData()

        Handler(Looper.getMainLooper()).postDelayed({
            webView.loadUrl(url)
        }, 200)
    }

    private fun initNotifications(webAppUrl: String) {
        val wasFirebaseMode = prefsManager.isFirebaseMode

        Thread {
            val isFirebaseConfigured = firebaseConfigChecker.checkStatus(webAppUrl)

            runOnUiThread {
                if (isFirebaseConfigured) {
                    Log.d(TAG, "Firebase is configured, switching to FCM")
                    prefsManager.isFirebaseMode = true
                    SubscriberServiceManager.stop(this)

                    if (!wasFirebaseMode) {
                        Toast.makeText(this, "Firebase notifications configured", Toast.LENGTH_SHORT).show()
                    }

                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            Thread {
                                firebaseTokenRegistrar.registerToken(token, webAppUrl)
                            }.start()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to get FCM token", e)
                        }
                } else {
                    Log.d(TAG, "Firebase not configured, using ntfy")
                    prefsManager.isFirebaseMode = false

                    if (wasFirebaseMode) {
                        Toast.makeText(this, "Firebase removed, switching to ntfy", Toast.LENGTH_SHORT).show()
                    }

                    fetchNtfyConfigAndConnect(webAppUrl)
                }
            }
        }.start()
    }

    fun fetchNtfyConfigAndConnect(webAppUrl: String) {
        Log.d(TAG, "fetchNtfyConfigAndConnect called with: $webAppUrl")
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

                        Handler(Looper.getMainLooper()).postDelayed({
                            SubscriberServiceManager.refresh(this)
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
        prefsManager.clearNtfyConfig()
        SubscriberServiceManager.refresh(this)
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

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        webView.clearSslPreferences()
        webView.clearCache(true)
        webView.destroy()
        super.onDestroy()
    }
}