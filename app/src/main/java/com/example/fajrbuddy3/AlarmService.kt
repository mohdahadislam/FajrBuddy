package com.example.fajrbuddy3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isServiceRunning = false
    private val handler = Handler(Looper.getMainLooper())

    // Fade-in Logic
    private var currentVolume = 0.05f // Start at 5%
    private val maxVolume = 1.0f
    private val fadeStep = 0.02f // Increase by 2% every step
    private val fadeInterval = 1000L // Run every 1 second

    private val userUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                checkAndForceLaunch()
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning) {
                checkAndForceLaunch()
                handler.postDelayed(this, 5000)
            }
        }
    }

    // NEW: Volume Fade-In Loop
    private val volumeRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                if (currentVolume < maxVolume) {
                    currentVolume += fadeStep
                    if (currentVolume > maxVolume) currentVolume = maxVolume

                    try {
                        mediaPlayer?.setVolume(currentVolume, currentVolume)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Loop again in 1 second
                    handler.postDelayed(this, fadeInterval)
                }
            }
        }
    }

    private fun checkAndForceLaunch() {
        val prefs = getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)
        val hasInteracted = prefs.getBoolean("ALARM_INTERACTED", false)

        if (isServiceRunning && !hasInteracted) {
            forceLaunchActivity()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(userUnlockReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            "STOP_ALARM" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "PAUSE_SOUND" -> {
                pauseSoundForGracePeriod()
                return START_STICKY
            }
        }

        isServiceRunning = true

        getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("ALARM_RINGING", true)
            .apply()

        startAlarm()
        handler.postDelayed(watchdogRunnable, 5000)

        return START_STICKY
    }

    private fun pauseSoundForGracePeriod() {
        // Stop fading when paused
        handler.removeCallbacks(volumeRunnable)

        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()

            // Schedule Resume after 30 seconds
            handler.postDelayed({
                if (isServiceRunning && mediaPlayer != null) {
                    try {
                        // Resume at MAX volume to ensure they wake up now
                        mediaPlayer?.setVolume(1.0f, 1.0f)
                        mediaPlayer?.start()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }, 30 * 1000L)
        }
    }

    private fun startAlarm() {
        val channelId = "FajrAlarmChannel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Fajr Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Fajr Time")
            .setContentText("Tap to Open App")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP ALARM", stopPendingIntent)
            .build()

        startForeground(1, notification)
        forceLaunchActivity()
        playAlarmSound()
    }

    private fun forceLaunchActivity() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(this, AlarmActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun playAlarmSound() {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()

                // Set initial low volume
                currentVolume = 0.05f
                setVolume(currentVolume, currentVolume)

                start()
            }

            // Start the Fade-In Loop
            handler.post(volumeRunnable)

        } catch (e: Exception) { e.printStackTrace() }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator?.vibrate(longArrayOf(0, 1000, 1000), 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("ALARM_RINGING", false)
            .apply()

        try { unregisterReceiver(userUnlockReceiver) } catch (e: IllegalArgumentException) {}

        // Clean up ALL handlers
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(volumeRunnable)
        handler.removeCallbacksAndMessages(null)

        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
    }
}