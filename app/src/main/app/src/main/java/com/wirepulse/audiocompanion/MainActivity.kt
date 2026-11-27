package com.wirepulse.audiocompanion

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import java.io.File
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
    private var lastKind: String? = null
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) wanted.add("android.permission.BLUETOOTH_CONNECT")
            val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
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
                        val wanted = params?.acceptTypes?.firstOrNull { !it.isNullOrBlank() }
                        type = if (wanted != null && wanted.contains("/")) wanted else "*/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "Choose a file"), FILE_REQUEST)
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

    override fun onResume() {
        super.onResume()
        lastConnectedState = null
        syncEarphoneState()
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

        @JavascriptInterface
        fun saveBackup(json: String): String {
            return try {
                writeBackup(json)
                "ok"
            } catch (e: Exception) {
                "err:" + (e.message ?: "unknown")
            }
        }

        @JavascriptInterface
        fun hapticS(kind: String, level: Int) {
            runOnUiThread { doHapticS(kind, level) }
        }
    }

    // Saves the backup file in the phone's Downloads folder so it survives uninstalling the app.
    @Suppress("DEPRECATION")
    private fun writeBackup(json: String) {
        val name = "WirePulse-backup.json"
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            var target: Uri? = null
            val q = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "%"),
                null
            )
            q?.use { c ->
                if (c.moveToFirst()) {
                    target = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                }
            }
            var done = false
            if (target != null) {
                try {
                    resolver.openOutputStream(target!!, "wt")?.use { it.write(bytes) }
                    done = true
                } catch (e: Exception) {
                    done = false
                }
            }
            if (!done) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("cannot create file")
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
    }

    @Suppress("DEPRECATION")
    private fun doHapticS(kind: String, level: Int) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            val k = when (level) { 1 -> 0.55f; 3 -> 1.0f; else -> 0.8f }
            val t: LongArray
            val a: IntArray
            when (kind) {
                "key" -> { t = longArrayOf(0, 14, 20, 7); a = intArrayOf(0, 255, 0, 110) }
                "tick" -> { t = longArrayOf(0, 6); a = intArrayOf(0, 190) }
                "click" -> { t = longArrayOf(0, 16, 24, 8); a = intArrayOf(0, 255, 0, 130) }
                "heavy" -> { t = longArrayOf(0, 34, 36, 16); a = intArrayOf(0, 255, 0, 170) }
                "alert" -> { t = longArrayOf(0, 200, 100, 200); a = intArrayOf(0, 255, 0, 255) }
                else -> { t = longArrayOf(0, 10); a = intArrayOf(0, 180) }
            }
            val tt = LongArray(t.size) { i -> if (i % 2 == 1) (t[i] * (0.6f + 0.4f * k)).toLong().coerceAtLeast(4L) else t[i] }
            val aa = IntArray(a.size) { i -> if (a[i] == 0) 0 else (a[i] * k).toInt().coerceIn(20, 255) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(tt, aa, -1))
                else v.vibrate(VibrationEffect.createWaveform(tt, -1))
            } else {
                v.vibrate(tt, -1)
            }
        } catch (e: Exception) {
            doHaptic(if (kind == "key") "click" else kind)
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
        var kind = "jack"
        var label = "Wired Headset"

        // priority: 3.5 mm jack, then USB-C, then Bluetooth
        val jack = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        val bt = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_HEARING_AID))
        }
        if (jack != null) {
            connected = true; isUsb = false; kind = "jack"
            label = jack.productName?.toString()?.takeIf { it.isNotBlank() } ?: "3.5mm Wired IEM"
        } else if (usb != null) {
            connected = true; isUsb = true; kind = "usb"
            label = usb.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB-C DAC / IEM"
        } else if (bt != null) {
            connected = true; isUsb = false; kind = "bt"
            label = bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth earphones"
        }

        if (lastConnectedState == connected && lastKind == kind) return
        lastConnectedState = connected
        lastKind = kind

        val safeLabel = label.replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "if(window.onNativeAndroidEarphoneState){window.onNativeAndroidEarphoneState($connected, '$safeLabel', $isUsb, '$kind');}",
                null
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        lastConnectedState = null
        syncEarphoneState()
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
