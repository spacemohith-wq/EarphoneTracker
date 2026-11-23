package com.wirepulse.audiocompanion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private var lastConnectedState: Boolean? = null

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            syncEarphoneState()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            syncEarphoneState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    lastConnectedState = null
                    syncEarphoneState()
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
        audioManager.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
    }

    private fun syncEarphoneState() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var connected = false
        var isUsb = false
        var label = "Wired Headset"

        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    connected = true
                    isUsb = false
                    label = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "3.5mm Wired IEM"
                    break
                }
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    connected = true
                    isUsb = true
                    label = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB-C DAC / IEM"
                    break
                }
            }
        }

        if (lastConnectedState == connected) return
        lastConnectedState = connected

        val safeLabel = label.replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "if(window.onNativeAndroidEarphoneState){window.onNativeAndroidEarphoneState($connected, '$safeLabel', $isUsb);}",
                null
            )
        }
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        super.onDestroy()
    }
}
