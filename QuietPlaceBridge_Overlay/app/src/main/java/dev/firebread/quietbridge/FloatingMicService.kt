package dev.firebread.quietbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating, draggable microphone control that sits on top of Minecraft.
 *
 * Tap  -> start/stop the microphone bridge (MicBridgeService).
 * Drag  -> move it to a corner; the position is remembered.
 *
 * It is a real foreground service (type specialUse): the previous version was
 * started with startForegroundService() but never called startForeground(),
 * which crashes on Android 12+.
 */
class FloatingMicService : Service() {

    companion object {
        private const val TAG = "QuietBridgeOverlay"
        private const val CHANNEL_ID = "quietbridge_overlay"
        private const val NOTIFICATION_ID = 43
        private const val PREFS = "bridge"
        private const val REFRESH_MS = 250L
    }

    private lateinit var windowManager: WindowManager
    private var container: LinearLayout? = null
    private var micIcon: TextView? = null
    private var meterText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refreshUi = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)

        // Always foreground first: we are always started with startForegroundService().
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Grant \"Display over other apps\" for Quiet Place Bridge, then start the overlay again",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        buildOverlay()
        handler.post(refreshUi)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshUi)
        container?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {
            }
        }
        container = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating mic button", NotificationManager.IMPORTANCE_MIN)
            )
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Quiet Place floating mic button")
            .setContentText("Tap the floating button in-game to toggle the microphone")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
            ).roundToInt()

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 34, 34, 34))
                cornerRadius = dp(24).toFloat()
            }
        }

        micIcon = TextView(this).apply {
            text = "\uD83C\uDFA4" // 🎤
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        meterText = TextView(this).apply {
            text = "OFF"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.rgb(150, 150, 150))
            gravity = Gravity.CENTER
        }
        bubble.addView(micIcon)
        bubble.addView(meterText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE: never steal keyboard/touch focus from Minecraft.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ox", 45)
            y = prefs.getInt("oy", 260)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx.roundToInt()
                    params.y = startY + dy.roundToInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (ignored: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("ox", params.x).putInt("oy", params.y).apply()
                    } else {
                        toggleMicrophone()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
            container = bubble
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show the floating button: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** Keep the button and meter in sync with the real service state. */
    private fun updateUi() {
        val icon = micIcon ?: return
        val meter = meterText ?: return
        val on = MicBridgeService.running
        val pct = (MicBridgeService.level.coerceIn(0f, 1f) * 100).roundToInt()
        if (on) {
            icon.text = "\uD83D\uDD34" // 🔴
            meter.text = "$pct%"
            meter.setTextColor(if (MicBridgeService.speaking) Color.rgb(255, 90, 90) else Color.rgb(140, 230, 140))
        } else {
            icon.text = "\uD83C\uDFA4" // 🎤
            meter.text = "OFF"
            meter.setTextColor(Color.rgb(150, 150, 150))
        }
        container?.contentDescription =
            if (on) "Microphone monitoring active, $pct percent" else "Microphone monitoring off, tap to start"
    }

    private fun toggleMicrophone() {
        if (MicBridgeService.running) {
            stopService(Intent(this, MicBridgeService::class.java))
            Toast.makeText(this, "Microphone OFF", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Grant the Microphone permission in the app first",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val intent = Intent(this, MicBridgeService::class.java).apply {
            putExtra("chat_x", prefs.getInt("chatX", 45))
            putExtra("chat_y", prefs.getInt("chatY", 45))
            putExtra("threshold", prefs.getFloat("threshold", 0.024f))
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Toast.makeText(this, "Microphone ON", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start the microphone: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
