package com.wirepulse.audiocompanion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private var lastConnectedState: Boolean? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val volumeTicker = object : Runnable {
        override fun run() {
            pushVolume()
            uiHandler.postDelayed(this, 1500)
        }
    }

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
            addJavascriptInterface(NativeBridge(), "WPNative")

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "Choose audio"), FILE_REQUEST)
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    lastConnectedState = null
                    syncEarphoneState()
                    pushVolume()
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
        audioManager.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        uiHandler.postDelayed(volumeTicker, 1500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQUEST) {
            val uri = data?.data
            val result: Array<Uri>? = if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }


    inner class NativeBridge {
        @JavascriptInterface
        fun haptic(kind: String) {
            runOnUiThread { doHaptic(kind) }
        }
    }

    @Suppress("DEPRECATION")
    private fun doHaptic(kind: String) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return

        if (kind == "alert") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
            } else {
                v.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (kind) {
                "tick" -> VibrationEffect.EFFECT_TICK
                "click" -> VibrationEffect.EFFECT_CLICK
                "heavy" -> VibrationEffect.EFFECT_HEAVY_CLICK
                else -> VibrationEffect.EFFECT_TICK
            }
            v.vibrate(VibrationEffect.createPredefined(effect))
            return
        }

        val ms = when (kind) {
            "tick" -> 8L
            "click" -> 14L
            "heavy" -> 32L
            else -> 10L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(ms)
        }
    }

    private fun pushVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (cur * 100) / max else 0
        webView.post {
            webView.evaluateJavascript(
                "if(window.onNativeVolume){window.onNativeVolume($pct);}",
                null
            )
        }
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
        uiHandler.removeCallbacks(volumeTicker)
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        super.onDestroy()
    }

    companion object {
        private const val FILE_REQUEST = 202
    }
}
