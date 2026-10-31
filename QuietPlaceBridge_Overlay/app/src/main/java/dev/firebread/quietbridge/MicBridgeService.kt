package dev.firebread.quietbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Foreground service that measures the real microphone level (AudioRecord) and
 * reports it to the Minecraft add-on through the Shizuku `input` bridge.
 *
 * This service never records to a file and never uploads anything: samples are
 * converted to an RMS level in memory and discarded immediately.
 */
class MicBridgeService : Service() {

    companion object {
        private const val TAG = "QuietBridge"
        private const val CHANNEL_ID = "quietbridge_mic"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 16000

        /** Shared with the overlay service; safe to read from the UI thread. */
        @Volatile var running: Boolean = false
        @Volatile var level: Float = 0f      // live display level 0..1
        @Volatile var speaking: Boolean = false // true when RMS >= threshold right now
    }

    @Volatile private var chatX = 45
    @Volatile private var chatY = 45
    @Volatile private var threshold = 0.024f

    /** Last state the game was told about (used to send a clean "silent" on stop). */
    @Volatile private var addonKnowsSpeaking = false

    private var audioRecord: AudioRecord? = null

    // Single-threaded sender so chat taps/text/enter never interleave.
    private val senderExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "QuietPlaceCommandSender") }
    private val sendLock = Any()
    private var queuedCommand: String? = null
    private var sending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            chatX = it.getIntExtra("chat_x", chatX)
            chatY = it.getIntExtra("chat_y", chatY)
            threshold = it.getFloatExtra("threshold", threshold)
        }

        // Always enter the foreground first: this service is always started with
        // startForegroundService(), and skipping startForeground() crashes on Android 12+.
        startForegroundCompat()

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO is not granted; microphone service stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            thread(name = "QuietPlaceMicMonitor") { monitorMic() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        speaking = false
        level = 0f
        try {
            audioRecord?.stop()
        } catch (ignored: IllegalStateException) {
        }
        try {
            audioRecord?.release()
        } catch (ignored: Exception) {
        }
        audioRecord = null
        // Tell the add-on we went quiet so the monster stops treating us as noisy.
        if (addonKnowsSpeaking) {
            runShell(MicLevel.stateCommand(false))
        }
        senderExecutor.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Microphone bridge", NotificationManager.IMPORTANCE_MIN)
            )
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Quiet Place mic bridge")
            .setContentText("Listening for sound levels for Minecraft")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        // FOREGROUND_SERVICE_TYPE_MICROPHONE is API 30+; the 2-arg call is fine below that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun monitorMic() {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        val bufferSize = maxOf(minBuffer * 2, 8192)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, channel, encoding, bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not create AudioRecord", e)
            running = false
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${record.state})")
            record.release()
            running = false
            stopSelf()
            return
        }
        audioRecord = record

        val buffer = ShortArray(bufferSize)
        var lastSendAt = 0L

        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (ignored: Exception) {
            }
            record.startRecording()
            Log.i(TAG, "Mic monitor started (threshold=$threshold)")

            while (running && !Thread.currentThread().isInterrupted) {
                val count = record.read(buffer, 0, buffer.size)
                if (count > 0) {
                    val rms = MicLevel.rms(buffer, count)
                    val levelNow = MicLevel.displayLevel(rms)
                    level = levelNow
                    val above = MicLevel.isAboveThreshold(rms, threshold)
                    speaking = above

                    val now = System.currentTimeMillis()
                    // A state flip (speaking/silent) always wins the next send slot; while
                    // noisy we keep a level heartbeat so the add-on can tell the bridge is
                    // alive; while quiet we send nothing more after the flip.
                    val stateFlipPending = above != addonKnowsSpeaking
                    val justified = above || stateFlipPending
                    if (justified && now - lastSendAt >= MicLevel.MIN_SEND_INTERVAL_MS) {
                        val payload = if (stateFlipPending) MicLevel.stateCommand(above) else MicLevel.command(levelNow)
                        if (enqueue(payload)) {
                            addonKnowsSpeaking = above
                            lastSendAt = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mic monitor crashed", e)
        } finally {
            try {
                record.stop()
            } catch (ignored: IllegalStateException) {
            }
            record.release()
            if (audioRecord === record) audioRecord = null
            running = false
            level = 0f
            speaking = false
            Log.i(TAG, "Mic monitor stopped")
        }
    }

    /**
     * Queue a chat command for the single sender thread. Returns false when Shizuku
     * is unavailable so the caller can retry later instead of losing the update.
     */
    @Suppress("ReturnCount")
    private fun enqueue(command: String): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku unavailable; not sent: $command")
            return false
        }
        synchronized(sendLock) {
            if (sending) {
                queuedCommand = command // latest value wins
                return true
            }
            sending = true
        }
        senderExecutor.execute {
            var next: String? = command
            while (next != null) {
                runShell(next!!)
                synchronized(sendLock) {
                    next = queuedCommand
                    queuedCommand = null
                    if (next == null) sending = false
                }
            }
        }
        return true
    }

    /** Run one command through Shizuku's shell: open chat, type, press enter. */
    private fun runShell(command: String) {
        try {
            // Minecraft's chat treats "%s" as a space when injected via `input text`.
            val text = command.replace(" ", "%s")
            val shell = "input tap $chatX $chatY; sleep 0.30; input text '$text'; sleep 0.12; input keyevent 66"
            val process = Shizuku.newProcess(arrayOf("sh", "-c", shell), null, null)
            val exit = process.waitFor()
            process.inputStream.close()
            process.errorStream.close()
            process.destroy()
            Log.i(TAG, "sent [$command] exit=$exit")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send [$command]", e)
        }
    }
}
