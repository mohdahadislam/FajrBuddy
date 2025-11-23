package com.example.fajrbuddy3

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fajrbuddy3.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var alarmManager: AlarmManager
    private lateinit var prefs: SharedPreferences
    private var nfcAdapter: NfcAdapter? = null
    private var isProgrammaticUpdate = false
    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)

        if (checkActiveAlarmRedirect()) return

        val savedTag = prefs.getString("SAVED_TAG_ID", null)
        if (savedTag == null) {
            val intent = Intent(this, WelcomeActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        binding.timePicker.setIs24HourView(true)

        // --- INTERACTION LOGIC ---

        // 1. SCROLL EVENT (The only one that shows the button)
        binding.timePicker.setOnTimeChangedListener { view, _, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            // Pass TRUE to show button
            wakeUpTimePickerUI(showButton = true)
        }

        // 2. TAP EVENTS (Only brighten the card, don't show button)
        binding.cardStatus.setOnClickListener {
            wakeUpTimePickerUI(showButton = false)
        }

        binding.cardTime.setOnClickListener {
            wakeUpTimePickerUI(showButton = false)
        }

        checkNotificationPermission()
        updateAlarmStatusUI()

        binding.switchAlarm.setOnCheckedChangeListener { view, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener

            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

            if (isChecked) {
                if (checkOverlayPermission()) {
                    setAlarmFromMemory()
                } else {
                    view.isChecked = false
                }
            } else {
                cancelAlarm()
            }
            updateVisualState(binding.switchAlarm.isChecked)
            val hour = prefs.getInt("ALARM_HOUR", -1)
            val minute = prefs.getInt("ALARM_MINUTE", -1)
            updateCountdownUI(binding.switchAlarm.isChecked, hour, minute)
        }

        binding.btnSetAlarm.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (checkOverlayPermission()) {
                saveAndSetAlarm()
            }
        }

        binding.tvBatteryHint.setOnClickListener {
            checkBatteryOptimization()
        }

        handleIntent(intent)
    }

    // --- HELPER: Centralized Wake-Up Logic ---
    private fun wakeUpTimePickerUI(showButton: Boolean) {
        // 1. Always Brighten Picker Card (User is looking at it)
        if (binding.cardTime.alpha < 1f) {
            binding.cardTime.animate().alpha(1f).setDuration(300).start()
        }

        // 2. Only Show Button if specifically requested (i.e., Time Changed)
        if (showButton) {
            val isAlarmSaved = prefs.getInt("ALARM_HOUR", -1) != -1
            val btnText = if (isAlarmSaved) "Update Alarm" else "Save Alarm"

            if (binding.btnSetAlarm.visibility != View.VISIBLE) {
                binding.btnSetAlarm.text = btnText
                binding.btnSetAlarm.visibility = View.VISIBLE
                binding.btnSetAlarm.alpha = 0f
                binding.btnSetAlarm.animate().alpha(1f).setDuration(200).start()
            }

            if (!binding.btnSetAlarm.isEnabled || binding.btnSetAlarm.text == "Saved ✓") {
                binding.btnSetAlarm.isEnabled = true
                binding.btnSetAlarm.text = btnText
                binding.btnSetAlarm.alpha = 1f
            }
        }
    }

    // --- ANIMATION LOGIC ---

    private fun animateButtonAndHide() {
        binding.btnSetAlarm.text = "Saved ✓"
        binding.btnSetAlarm.isEnabled = false

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed) {
                binding.btnSetAlarm.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        binding.btnSetAlarm.visibility = View.GONE

                        val isAlarmSaved = prefs.getInt("ALARM_HOUR", -1) != -1
                        binding.btnSetAlarm.text = if (isAlarmSaved) "Update Alarm" else "Save Alarm"
                        binding.btnSetAlarm.isEnabled = true
                        binding.btnSetAlarm.alpha = 1f

                        if (binding.switchAlarm.isChecked) {
                            binding.cardTime.animate().alpha(0.3f).setDuration(500).start()
                        }
                    }
                    .start()
            }
        }, 1500)
    }

    // --- ALARM LOGIC ---

    private fun saveAndSetAlarm() {
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
                Toast.makeText(this, "Please allow setting exact alarms", Toast.LENGTH_LONG).show()
                return
            }
        }

        prefs.edit()
            .putInt("ALARM_HOUR", hour)
            .putInt("ALARM_MINUTE", minute)
            .putBoolean("ALARM_ENABLED", true)
            .apply()

        prefs.edit().putBoolean("ALARM_INTERACTED", false).apply()
        prefs.edit().putBoolean("SNOOZE_USED", false).apply()

        AlarmScheduler.scheduleAlarm(this)
        updateAlarmStatusUI()
        showTimeRemainingToast(hour, minute)
        animateButtonAndHide()
    }

    private fun showTimeRemainingToast(hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val alarmTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }

        if (alarmTime.before(now)) {
            alarmTime.add(Calendar.DAY_OF_YEAR, 1)
        }

        val diffMs = alarmTime.timeInMillis - now.timeInMillis
        val hours = diffMs / (1000 * 60 * 60)
        val mins = (diffMs / (1000 * 60)) % 60

        val msg = if (hours > 0) {
            "Alarm set for $hours hr $mins min from now"
        } else {
            "Alarm set for $mins min from now"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun setAlarmFromMemory() {
        val hour = prefs.getInt("ALARM_HOUR", -1)
        val minute = prefs.getInt("ALARM_MINUTE", -1)
        if (hour != -1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                    binding.switchAlarm.isChecked = false
                    return
                }
            }
            prefs.edit().putBoolean("ALARM_ENABLED", true).apply()
            prefs.edit().putBoolean("ALARM_INTERACTED", false).apply()
            prefs.edit().putBoolean("SNOOZE_USED", false).apply()
            AlarmScheduler.scheduleAlarm(this)
        }
    }

    private fun updateAlarmStatusUI() {
        isProgrammaticUpdate = true

        val hour = prefs.getInt("ALARM_HOUR", -1)
        val minute = prefs.getInt("ALARM_MINUTE", -1)
        val isEnabled = prefs.getBoolean("ALARM_ENABLED", false)

        if (hour != -1 && minute != -1) {
            val formattedTime = String.format("%02d:%02d", hour, minute)
            binding.tvSavedTime.text = formattedTime

            if (binding.btnSetAlarm.text != "Saved ✓") {
                binding.btnSetAlarm.visibility = View.GONE
            }

            binding.switchAlarm.isEnabled = true
            binding.switchAlarm.isChecked = isEnabled
            updateVisualState(isEnabled)
            updateCountdownUI(isEnabled, hour, minute)
        } else {
            binding.tvSavedTime.text = "--:--"
            binding.btnSetAlarm.text = "Save Alarm"
            binding.btnSetAlarm.visibility = View.VISIBLE
            binding.switchAlarm.isEnabled = false
            binding.switchAlarm.isChecked = false
            updateVisualState(false)
            updateCountdownUI(false, 0, 0)
        }

        isProgrammaticUpdate = false
    }

    private fun updateVisualState(isOn: Boolean) {
        val accentColor = ContextCompat.getColor(this, R.color.app_accent)
        val accentTrack = ContextCompat.getColor(this, R.color.switch_track_active)
        val grayColor = ContextCompat.getColor(this, R.color.text_secondary)
        val whiteColor = ContextCompat.getColor(this, R.color.text_primary)
        val grayTrack = ContextCompat.getColor(this, R.color.switch_track_inactive)

        if (isOn) {
            binding.tvSavedTime.setTextColor(whiteColor)
            binding.imgStatusIcon.imageTintList = ColorStateList.valueOf(accentColor)
            binding.switchAlarm.thumbTintList = ColorStateList.valueOf(accentColor)
            binding.switchAlarm.trackTintList = ColorStateList.valueOf(accentTrack)

            // Dim Picker if alarm ON and button HIDDEN
            if (binding.btnSetAlarm.visibility == View.GONE) {
                binding.cardTime.alpha = 0.3f
            }
        } else {
            binding.tvSavedTime.setTextColor(grayColor)
            binding.imgStatusIcon.imageTintList = ColorStateList.valueOf(grayColor)
            binding.switchAlarm.thumbTintList = ColorStateList.valueOf(grayColor)
            binding.switchAlarm.trackTintList = ColorStateList.valueOf(grayTrack)

            // Brighten Picker if alarm OFF
            binding.cardTime.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun updateCountdownUI(isEnabled: Boolean, hour: Int, minute: Int) {
        binding.tvSubHeader.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        if (isEnabled) {
            val now = Calendar.getInstance()
            val alarmTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            if (alarmTime.before(now)) alarmTime.add(Calendar.DAY_OF_YEAR, 1)
            val diffMs = alarmTime.timeInMillis - now.timeInMillis
            val hours = diffMs / (1000 * 60 * 60)
            val mins = (diffMs / (1000 * 60)) % 60
            binding.tvSubHeader.text = "Rings in ${hours}hr ${mins}min"
        } else {
            binding.tvSubHeader.text = "No alarm set"
        }
    }

    private fun cancelAlarm() {
        AlarmScheduler.cancelAlarm(this)
        prefs.edit().putBoolean("ALARM_ENABLED", false).apply()
        val hour = prefs.getInt("ALARM_HOUR", -1)
        val minute = prefs.getInt("ALARM_MINUTE", -1)
        updateCountdownUI(false, hour, minute)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) { if (NfcAdapter.ACTION_TAG_DISCOVERED == intent?.action || NfcAdapter.ACTION_TECH_DISCOVERED == intent?.action) {} }
    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
    override fun onTagDiscovered(tag: Tag?) { }

    override fun onResume() {
        super.onResume()
        if (checkActiveAlarmRedirect()) return
        checkNfcEnabled()
        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            val options = Bundle()
            nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
        }
        updateAlarmStatusUI()
    }

    private fun checkActiveAlarmRedirect(): Boolean {
        val isRinging = prefs.getBoolean("ALARM_RINGING", false)
        if (isRinging) {
            val intent = Intent(this, AlarmActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
            return true
        }
        return false
    }

    private fun checkNfcEnabled() {
        if (nfcAdapter != null && !nfcAdapter!!.isEnabled) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enable NFC")
                .setMessage("NFC is required for the alarm to work properly.\n\nTip: Keeping NFC turned on consumes zero battery.")
                .setPositiveButton("Settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun checkNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } } }
    private fun checkOverlayPermission(): Boolean { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { if (!Settings.canDrawOverlays(this)) { showPermissionDialog("Full Screen Alarm", "To ensure the alarm wakes you up when the screen is locked, FajrBuddy needs permission to 'Display over other apps'.") { try { val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")); startActivity(intent) } catch (e: Exception) {} }; return false } }; return true }
    private fun checkBatteryOptimization() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; if (!pm.isIgnoringBatteryOptimizations(packageName)) { showPermissionDialog("Reliability Check", "To prevent the system from killing the alarm while you sleep, please allow FajrBuddy to ignore battery optimizations.") { try { val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")); startActivity(intent) } catch (e: Exception) {} } } else { Toast.makeText(this, "Battery optimization is already enabled!", Toast.LENGTH_SHORT).show() } } }
    private fun showPermissionDialog(title: String, message: String, action: () -> Unit) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("Enable") { _, _ -> action() }.setNegativeButton("Later", null).show() }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
}