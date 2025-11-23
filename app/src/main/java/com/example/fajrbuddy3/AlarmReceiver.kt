package com.example.fajrbuddy3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. FORCE SCREEN ON (The "Flashlight" Effect)
        // We use SCREEN_BRIGHT_WAKE_LOCK to physically light up the screen.
        // ACQUIRE_CAUSES_WAKEUP forces the device to wake up immediately from sleep.
        // This ensures the user sees light even if the Full Screen Activity is blocked/delayed.
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FajrBuddy::AlarmWakeLock"
        )

        // 2. Hold the light for 30 seconds (User Request)
        // This is enough time to grab attention, but releases automatically to save battery if ignored.
        wakeLock.acquire(30 * 1000L)

        // 3. Start the Service (Sound & UI)
        val serviceIntent = Intent(context, AlarmService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}