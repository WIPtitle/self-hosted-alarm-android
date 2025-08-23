package com.wiptitle.ntfy_webapp_android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.service.NtfyService

class SettingsFragment : Fragment() {
    private lateinit var prefsManager: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())

        setupWebAppSettings(view)
        setupNtfySettings(view)
    }

    private fun setupWebAppSettings(view: View) {
        val webappUrlInput = view.findViewById<TextInputEditText>(R.id.webapp_url_input)
        val saveWebappButton = view.findViewById<Button>(R.id.save_webapp_button)

        webappUrlInput.setText(prefsManager.webAppUrl)

        saveWebappButton.setOnClickListener {
            val url = webappUrlInput.text.toString()
            if (url.isNotEmpty()) {
                prefsManager.webAppUrl = url
                (activity as? MainActivity)?.reloadWebView()
                Toast.makeText(context, "WebApp URL saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupNtfySettings(view: View) {
        val ntfyUrlInput = view.findViewById<TextInputEditText>(R.id.ntfy_url_input)
        val ntfyTopicInput = view.findViewById<TextInputEditText>(R.id.ntfy_topic_input)
        val ntfyUsernameInput = view.findViewById<TextInputEditText>(R.id.ntfy_username_input)
        val ntfyPasswordInput = view.findViewById<TextInputEditText>(R.id.ntfy_password_input)
        val saveNtfyButton = view.findViewById<Button>(R.id.save_ntfy_button)
        val testNtfyButton = view.findViewById<Button>(R.id.test_ntfy_button)

        ntfyUrlInput.setText(prefsManager.ntfyUrl)
        ntfyTopicInput.setText(prefsManager.ntfyTopic)
        ntfyUsernameInput.setText(prefsManager.ntfyUsername)
        ntfyPasswordInput.setText(prefsManager.ntfyPassword)

        saveNtfyButton.setOnClickListener {
            val url = ntfyUrlInput.text.toString()
            val topic = ntfyTopicInput.text.toString()

            if (url.isEmpty() || topic.isEmpty()) {
                Toast.makeText(context, "URL and Topic are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val urlChanged = url != prefsManager.ntfyUrl
            val topicChanged = topic != prefsManager.ntfyTopic

            prefsManager.ntfyUrl = url
            prefsManager.ntfyTopic = topic
            prefsManager.ntfyUsername = ntfyUsernameInput.text?.toString()
            prefsManager.ntfyPassword = ntfyPasswordInput.text?.toString()
            prefsManager.ntfyEnabled = true

            if (urlChanged || topicChanged) {
                prefsManager.clearNtfyLastMessage()
            }

            (activity as? MainActivity)?.apply {
                restartNtfyService()
            }

            Toast.makeText(context, "Ntfy settings saved and service started", Toast.LENGTH_SHORT).show()
        }

        testNtfyButton.setOnClickListener {
            if (prefsManager.isNtfyConfigured()) {
                Toast.makeText(context, "Connection test started. Check notifications.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Please configure ntfy first", Toast.LENGTH_SHORT).show()
            }
        }
    }
}