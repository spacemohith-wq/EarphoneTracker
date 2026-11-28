package com.wirepulse.audiocompanion

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
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
            if (Build.VERSION.SDK_INT >= 33) wanted.add("android.permission.POST_NOTIFICATIONS")
            val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { startTracker(applicationContext) } catch (e: Exception) { }

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
        fun drainTrack(): String {
            return try { TrackStore.drain(applicationContext).toString() } catch (e: Exception) { "{}" }
        }

        @JavascriptInterface
        fun flushTrack() {
            try { TrackStore.tick(applicationContext) } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun hapticS(kind: String, level: Int) {
            runOnUiThread { doHapticS(kind, level) }
        }
    }

    // Saves ONE backup file in the phone's Downloads folder (overwritten each time, never duplicated).
    private fun tryWrite(uri: Uri, bytes: ByteArray): Boolean {
        for (mode in arrayOf("wt", "rwt", "w")) {
            try {
                val os = contentResolver.openOutputStream(uri, mode) ?: continue
                os.use { it.write(bytes) }
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun writeBackup(json: String) {
        val name = "WirePulse-backup.json"
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val sp = getSharedPreferences("wp_bk", Context.MODE_PRIVATE)
            // 1) reuse the file we wrote last time
            val saved = sp.getString("uri", null)
            if (saved != null && tryWrite(Uri.parse(saved), bytes)) return

            // 2) look for existing backup files (also tidies up duplicates from older versions)
            val found = ArrayList<Uri>()
            val q = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                arrayOf("WirePulse-backup%", Environment.DIRECTORY_DOWNLOADS + "%"),
                MediaStore.MediaColumns._ID + " DESC"
            )
            q?.use { c ->
                while (c.moveToNext()) {
                    found.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                }
            }
            var keep: Uri? = null
            val extras = ArrayList<Uri>()
            for (u in found) {
                if (keep == null && tryWrite(u, bytes)) keep = u else extras.add(u)
            }
            if (keep == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("cannot create file")
                if (!tryWrite(uri, bytes)) throw Exception("cannot write file")
                keep = uri
            }
            sp.edit().putString("uri", keep.toString()).apply()
            if (extras.isNotEmpty()) {
                Thread {
                    for (u in extras) {
                        try { resolver.delete(u, null, null) } catch (e: Exception) { }
                    }
                }.start()
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
            if (kind == "alert") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
                else v.vibrate(longArrayOf(0, 200, 100, 200), -1)
                return
            }
            // Three clearly different feels: Soft = a faint flick, Medium = a firm tap, Strong = a long heavy thump
            val lvl = if (level < 1) 1 else if (level > 3) 3 else level
            val base = when (kind) { "tick" -> 0.6f; "heavy" -> 1.4f; else -> 1.0f }
            val ms = (when (lvl) { 1 -> 9L; 2 -> 28L; else -> 70L } * base).toLong().coerceAtLeast(5L)
            val amp = when (lvl) { 1 -> 60; 2 -> 150; else -> 255 }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(ms, amp))
                else v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
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


// ------------------------------------------------------------------
// Background listening-time tracking (keeps working when the app is closed)
// ------------------------------------------------------------------
fun startTracker(c: Context) {
    val i = Intent(c, TrackerService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try { startTracker(context.applicationContext) } catch (e: Exception) { }
    }
}

class TrackerService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            try { TrackStore.tick(applicationContext) } catch (e: Exception) { }
            h.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startFg()
        h.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacks(loop)
        super.onDestroy()
    }

    private fun startFg() {
        val channel = "wp_track"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channel, "Listening tracker", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel) else Notification.Builder(this)
        val n = b.setContentTitle("WirePulse AMG")
            .setContentText("Tracking your listening time")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }
}

object TrackStore {
    private const val PREF = "wp_track"

    private fun detect(am: AudioManager): Triple<Boolean, String, String> {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val jack = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        val bt = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_HEARING_AID))
        }
        val label: String
        val kind: String
        if (jack != null) {
            kind = "jack"; label = jack.productName?.toString()?.takeIf { it.isNotBlank() } ?: "3.5mm Wired IEM"
        } else if (usb != null) {
            kind = "usb"; label = usb.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB-C DAC / IEM"
        } else if (bt != null) {
            kind = "bt"; label = bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth earphones"
        } else {
            return Triple(false, "", "none")
        }
        return Triple(true, label.replace("'", ""), kind)
    }

    private fun addChunk(chunks: JSONArray, day: String, label: String, vol: Double, sec: Double) {
        for (i in 0 until chunks.length()) {
            val c = chunks.getJSONObject(i)
            if (c.getString("d") == day && c.getString("l") == label && Math.abs(c.getDouble("v") - vol) < 0.001) {
                c.put("s", c.getDouble("s") + sec)
                return
            }
        }
        chunks.put(JSONObject().put("d", day).put("l", label).put("v", vol).put("s", sec))
    }

    @Synchronized
    fun tick(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastT = sp.getLong("lastT", 0L)
        val prevActive = sp.getBoolean("prevActive", false)
        val info = detect(am)
        val active = info.first && am.isMusicActive
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maxV * 100.0) / 100.0
        var dt = if (lastT > 0L) (now - lastT) / 1000.0 else 0.0
        if (dt < 0) dt = 0.0

        val st = try { JSONObject(sp.getString("data", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val chunks = st.optJSONArray("chunks") ?: JSONArray()
        val sessions = st.optJSONArray("sessions") ?: JSONArray()
        var cur = st.optJSONObject("cur")

        // 1) count the time that just passed
        val counted = if (prevActive && dt > 0 && (active || !info.first)) Math.min(dt, if (active) 120.0 else 6.0) else 0.0
        if (counted > 0) {
            val label = cur?.optString("label") ?: info.second
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(now))
            addChunk(chunks, day, label, vol, counted)
            if (cur != null) {
                cur.put("sec", cur.optDouble("sec") + counted)
                cur.put("volSum", cur.optDouble("volSum") + vol * counted)
            }
        }

        // 2) close the session when the earphones go away (or change)
        if (cur != null && (!info.first || cur.optString("label") != info.second)) {
            val sec = cur.optDouble("sec")
            if (sec >= 5.0) {
                sessions.put(
                    JSONObject().put("start", cur.optLong("start")).put("sec", sec)
                        .put("device", cur.optString("label")).put("vol", cur.optDouble("volSum") / sec)
                )
            }
            cur = null
        }
        // 3) open a new session when earphones are in
        if (info.first && cur == null) {
            cur = JSONObject().put("start", now).put("label", info.second).put("sec", 0.0).put("volSum", 0.0)
        }

        st.put("chunks", chunks).put("sessions", sessions)
        if (cur != null) st.put("cur", cur) else st.remove("cur")
        sp.edit().putString("data", st.toString()).putLong("lastT", now).putBoolean("prevActive", active).apply()
    }

    @Synchronized
    fun drain(ctx: Context): JSONObject {
        tick(ctx)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val st = try { JSONObject(sp.getString("data", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = JSONObject()
        out.put("chunks", st.optJSONArray("chunks") ?: JSONArray())
        out.put("sessions", st.optJSONArray("sessions") ?: JSONArray())
        out.put("cur", st.optJSONObject("cur") ?: JSONObject.NULL)
        st.put("chunks", JSONArray()).put("sessions", JSONArray())
        sp.edit().putString("data", st.toString()).apply()
        return out
    }
}
