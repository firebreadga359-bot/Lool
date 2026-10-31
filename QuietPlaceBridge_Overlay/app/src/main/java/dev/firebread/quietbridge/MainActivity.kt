package dev.firebread.quietbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var chatX: EditText
    private lateinit var chatY: EditText
    private lateinit var threshold: EditText
    private val shizukuRequest = 51

    // Shizuku delivers its permission result through this listener, NOT through
    // onRequestPermissionsResult(); the old code never restarted the bridge.
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                startBridge()
            } else {
                Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        layout.addView(TextView(this).apply {
            text = "\uD83C\uDFA4 Quiet Place Mic Bridge"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "Measures real microphone RMS on this phone and reports the level to the " +
                "Minecraft add-on. Nothing is recorded to a file and nothing is uploaded. " +
                "The bridge types the command into Minecraft chat, so chat opens briefly " +
                "while you speak."
            textSize = 15f
        })
        chatX = field("Minecraft chat button X pixel", prefInt(prefs, "chatX", "x", 45).toString())
        chatY = field("Minecraft chat button Y pixel", prefInt(prefs, "chatY", "y", 45).toString())
        threshold = field("Sensitivity: raw RMS threshold (default 0.024)", formatThreshold(prefFloat(prefs, "threshold", 0.024f)))
        listOf(chatX, chatY, threshold).forEach { layout.addView(it) }

        // Slider is the friendly control; the EditText above stays in sync.
        layout.addView(TextView(this).apply { text = "Sensitivity slider (lower = picks up quieter sounds)" })
        val seek = SeekBar(this).apply {
            max = 100
            progress = thresholdToProgress(prefFloat(prefs, "threshold", 0.024f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) threshold.setText(formatThreshold(progressToThreshold(progress)))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(seek)

        layout.addView(TextView(this).apply {
            text = "The in-game meter shows the level the add-on received. For one-player " +
                "worlds the receiver targets the only player. Coordinates 45,260 are only a " +
                "starting guess — calibrate them by tapping the chat button position. Do not " +
                "use in a valuable world until you have tested it."
            textSize = 13f
        })
        layout.addView(TextView(this).apply {
            text = if (Shizuku.pingBinder()) "✅ Shizuku detected" else "⚠️ Shizuku not connected — open the Shizuku app and start it first"
        })
        layout.addView(Button(this).apply {
            text = "START MIC METER"
            setOnClickListener { startBridge() }
        })
        layout.addView(Button(this).apply {
            text = "STOP MIC METER"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MicBridgeService::class.java))
                Toast.makeText(this@MainActivity, "Mic OFF", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "SHOW FLOATING \uD83C\uDFA4 BUTTON"
            setOnClickListener { showFloating() }
        })
        layout.addView(Button(this).apply {
            text = "HIDE FLOATING BUTTON"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingMicService::class.java))
                Toast.makeText(this@MainActivity, "Floating button hidden", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(TextView(this).apply {
            text = "Setup: enable the behavior pack in a backup world; start Shizuku; grant " +
                "Shizuku + microphone + overlay permission; calibrate the chat coordinates; " +
                "start the bridge; return to Minecraft. This is a single-player noise bridge, " +
                "not player-to-player voice chat."
            textSize = 13f
        })
        setContentView(ScrollView(this).apply { addView(layout, ViewGroup.LayoutParams(-1, -2)) })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onStop()
    }

    private fun field(label: String, value: String) = EditText(this).apply {
        hint = label
        setText(value)
        singleLine = true
        contentDescription = label
    }

    private fun thresholdToProgress(thresholdValue: Float): Int =
        (((thresholdValue - 0.005f) / 0.095f) * 100).toInt().coerceIn(0, 100)

    private fun progressToThreshold(progress: Int): Float =
        0.005f + (progress.coerceIn(0, 100) / 100f) * 0.095f

    private fun formatThreshold(value: Float): String = String.format(Locale.US, "%.3f", value)

    /** Read settings, validating them, and hand them to both services. */
    private fun collectSettings(): Triple<Int, Int, Float>? {
        val x = chatX.text.toString().toIntOrNull()
        val y = chatY.text.toString().toIntOrNull()
        val t = threshold.text.toString().toFloatOrNull()
        if (x == null || y == null || t == null || t <= 0f) {
            Toast.makeText(this, "Check coordinates and threshold", Toast.LENGTH_LONG).show()
            return null
        }
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
            .putInt("chatX", x)
            .putInt("chatY", y)
            .putFloat("threshold", t)
            .apply()
        return Triple(x, y, t)
    }

    private fun startBridge() {
        val settings = collectSettings() ?: return
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Activate Shizuku first", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(shizukuRequest) // result arrives via the listener
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        val intent = Intent(this, MicBridgeService::class.java).apply {
            putExtra("chat_x", settings.first)
            putExtra("chat_y", settings.second)
            putExtra("threshold", settings.third)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Mic measurement started — return to Minecraft", Toast.LENGTH_LONG).show()
    }

    private fun showFloating() {
        collectSettings() ?: return
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(
                this,
                "Allow display over other apps, then return and tap SHOW FLOATING BUTTON again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val overlay = Intent(this, FloatingMicService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlay) else startService(overlay)
        Toast.makeText(this, "Floating mic button shown", Toast.LENGTH_SHORT).show()
    }

    /** Legacy prefs stored these as strings; tolerate both shapes. */
    private fun prefInt(prefs: android.content.SharedPreferences, key: String, legacyKey: String, fallback: Int): Int =
        try {
            prefs.getInt(key, prefs.getString(legacyKey, fallback.toString())?.toIntOrNull() ?: fallback)
        } catch (ignored: ClassCastException) {
            fallback
        }

    private fun prefFloat(prefs: android.content.SharedPreferences, key: String, fallback: Float): Float =
        try {
            prefs.getFloat(key, prefs.getString(key, fallback.toString())?.toFloatOrNull() ?: fallback)
        } catch (ignored: ClassCastException) {
            fallback
        }
}
