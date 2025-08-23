package com.wiptitle.ntfy_webapp_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.service.NtfyService

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var webView: WebView
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(this)

        setupToolbar()
        setupWebView()
        setupNavigation()
        setupBackPressHandler()
        requestNotificationPermission()
        startNtfyServiceIfNeeded()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
    }

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
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.loadUrl(prefsManager.webAppUrl)
    }

    private fun setupNavigation() {
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_webapp, R.id.nav_settings),
            drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_webapp -> {
                    navController.navigate(R.id.nav_webapp)
                    drawerLayout.closeDrawers()
                    webView.visibility = android.view.View.VISIBLE
                    findViewById<android.view.View>(R.id.nav_host_fragment).visibility = android.view.View.GONE
                    true
                }
                R.id.nav_settings -> {
                    navController.navigate(R.id.nav_settings)
                    drawerLayout.closeDrawers()
                    webView.visibility = android.view.View.GONE
                    findViewById<android.view.View>(R.id.nav_host_fragment).visibility = android.view.View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerLayout.isDrawerOpen(findViewById<NavigationView>(R.id.nav_view)) -> {
                        drawerLayout.closeDrawers()
                    }
                    webView.visibility == android.view.View.VISIBLE && webView.canGoBack() -> {
                        webView.goBack()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
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

    private fun startNtfyServiceIfNeeded() {
        if (prefsManager.ntfyEnabled && prefsManager.isNtfyConfigured()) {
            startNtfyService()
        }
    }

    fun startNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_START
        ContextCompat.startForegroundService(this, intent)
    }

    fun stopNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_STOP
        startService(intent)
    }

    fun restartNtfyService() {
        val intent = Intent(this, NtfyService::class.java)
        intent.action = NtfyService.ACTION_RESTART
        ContextCompat.startForegroundService(this, intent)
    }

    fun reloadWebView() {
        webView.loadUrl(prefsManager.webAppUrl)
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}