package com.example.fajrbuddy3

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fajrbuddy3.databinding.ActivityAlarmBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityAlarmBinding
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var prefs: SharedPreferences
    private var savedTagId: String? = null

    private var pulse1Animator: ObjectAnimator? = null
    private var pulse2Animator: ObjectAnimator? = null
    private var graceTimer: CountDownTimer? = null

    private var isUIInLockMode = true
    private var isAlarmActive = true
    private var hasPausedSound = false

    // --- EMERGENCY LOGIC CONFIG ---
    // 60 seconds * 10 ticks per second = 600 ticks
    private val MAX_EMERGENCY_TICKS = 600
    private val TICK_INTERVAL_MS = 100L

    private var emergencyHandler = Handler(Looper.getMainLooper())
    private var emergencyProgress = 0

    private val emergencyRunnable = object : Runnable {
        override fun run() {
            if (emergencyProgress < MAX_EMERGENCY_TICKS) {
                emergencyProgress++

                // Update Bar
                binding.progressEmergency.progress = emergencyProgress

                // Update Text with Countdown
                val secondsLeft = (MAX_EMERGENCY_TICKS - emergencyProgress) / 10
                binding.tvEmergency.text = "Keep holding: ${secondsLeft}s"
                binding.tvEmergency.setTextColor(getColor(R.color.error_red))

                // Loop
                emergencyHandler.postDelayed(this, TICK_INTERVAL_MS)
            } else {
                // Finished!
                Toast.makeText(applicationContext, "Emergency Override Activated", Toast.LENGTH_LONG).show()
                stopAlarmAndFinish()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            if (!isAlarmActive || isFinishing) return
            updateClock()
            checkAutoTransition()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        prefs = getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)
        savedTagId = prefs.getString("SAVED_TAG_ID", null)

        isAlarmActive = true
        hasPausedSound = false

        // Snooze Logic
        val snoozeUsed = prefs.getBoolean("SNOOZE_USED", false)
        if (snoozeUsed) {
            binding.btnSnooze.isEnabled = false
            binding.btnSnooze.text = "Snooze Used"
            binding.btnSnooze.alpha = 0.5f
        } else {
            binding.btnSnooze.setOnClickListener {
                handleSnooze()
            }
        }

        setShowWhenLockedAndTurnScreenOn()
        updateUIBasedOnLockState()

        binding.btnDismiss.setOnClickListener {
            handleDismissClick()
        }

        setupEmergencyButton()
    }

    private fun setupEmergencyButton() {
        binding.tvEmergency.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // RESET
                    emergencyProgress = 0
                    binding.progressEmergency.max = MAX_EMERGENCY_TICKS
                    binding.progressEmergency.progress = 0
                    binding.progressEmergency.visibility = View.VISIBLE

                    // Start Loop
                    emergencyHandler.removeCallbacks(emergencyRunnable)
                    emergencyHandler.post(emergencyRunnable)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // STOP
                    emergencyHandler.removeCallbacks(emergencyRunnable)

                    // Reset UI
                    binding.progressEmergency.visibility = View.INVISIBLE
                    binding.tvEmergency.setTextColor(getColor(R.color.text_secondary))
                    binding.tvEmergency.text = "Lost Tag?"
                    emergencyProgress = 0

                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun handleSnooze() {
        prefs.edit().putBoolean("SNOOZE_USED", true).apply()
        AlarmScheduler.scheduleSnooze(this)
        isAlarmActive = false
        handler.removeCallbacks(timeRunnable)
        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" }
        startService(stopIntent)
        Toast.makeText(this, "Snoozing for 3 minutes...", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isAlarmActive) {
            updateUIBasedOnLockState()
            handler.post(timeRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        handler.removeCallbacks(timeRunnable)
        stopPulseAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        graceTimer?.cancel()
        emergencyHandler.removeCallbacks(emergencyRunnable)
    }

    private fun updateClock() {
        val currentTime = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvCurrentTime.text = timeFormat.format(currentTime)
        val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        binding.tvDate.text = dateFormat.format(currentTime)
    }

    private fun checkAutoTransition() {
        if (!isAlarmActive) return
        val locked = isDeviceLocked()
        if (!locked && isUIInLockMode) {
            updateUIBasedOnLockState()
        } else if (locked && !isUIInLockMode) {
            updateUIBasedOnLockState()
        }
    }

    private fun updateUIBasedOnLockState() {
        if (isDeviceLocked()) {
            // LOCKED
            isUIInLockMode = true
            nfcAdapter?.disableReaderMode(this)

            binding.layoutClock.visibility = View.VISIBLE
            binding.btnSnooze.visibility = View.VISIBLE
            binding.layoutScanner.visibility = View.GONE
            binding.btnDismiss.visibility = View.VISIBLE
            binding.tvAlarmStatus.text = "Unlock to dismiss"

            binding.tvEmergency.visibility = View.GONE
            binding.progressEmergency.visibility = View.GONE

            stopPulseAnimation()
        } else {
            // UNLOCKED
            isUIInLockMode = false
            enableNfcScanning()

            prefs.edit().putBoolean("ALARM_INTERACTED", true).apply()

            if (!hasPausedSound) {
                hasPausedSound = true
                val intent = Intent(this, AlarmService::class.java).apply { action = "PAUSE_SOUND" }
                startService(intent)
                startGraceTimer()
            }

            binding.layoutClock.visibility = View.GONE
            binding.btnSnooze.visibility = View.GONE
            binding.layoutScanner.visibility = View.VISIBLE
            binding.btnDismiss.visibility = View.GONE
            binding.tvAlarmStatus.text = "Looking for tag..."

            binding.tvEmergency.visibility = View.VISIBLE
            binding.tvEmergency.setTextColor(getColor(R.color.text_secondary))

            startPulseAnimation()
        }
    }

    private fun startGraceTimer() {
        graceTimer?.cancel()
        binding.tvGraceTimer.visibility = View.VISIBLE
        graceTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.tvGraceTimer.text = "Sound resumes in ${seconds}s"
            }
            override fun onFinish() {
                binding.tvGraceTimer.text = "Sound Resumed!"
            }
        }.start()
    }

    private fun startPulseAnimation() {
        if (pulse1Animator?.isRunning == true) return
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
        pulse1Animator = ObjectAnimator.ofPropertyValuesHolder(binding.viewPulse1, scaleX, scaleY, alpha).apply { duration = 1500; repeatCount = ObjectAnimator.INFINITE; interpolator = AccelerateDecelerateInterpolator(); start() }
        pulse2Animator = ObjectAnimator.ofPropertyValuesHolder(binding.viewPulse2, scaleX, scaleY, alpha).apply { duration = 1500; repeatCount = ObjectAnimator.INFINITE; interpolator = AccelerateDecelerateInterpolator(); startDelay = 750; start() }
    }

    private fun stopPulseAnimation() { pulse1Animator?.cancel(); pulse2Animator?.cancel() }

    private fun enableNfcScanning() {
        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            val options = Bundle()
            nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (!isAlarmActive) return
        tag?.let {
            val scannedId = it.id.joinToString(":") { byte -> "%02x".format(byte) }
            runOnUiThread {
                if (savedTagId == null) {
                    Toast.makeText(this, "No tag registered! Closing alarm.", Toast.LENGTH_SHORT).show()
                    stopAlarmAndFinish()
                } else if (scannedId == savedTagId) {
                    Toast.makeText(this, "ALARM DISMISSED!", Toast.LENGTH_LONG).show()
                    stopAlarmAndFinish()
                } else {
                    binding.tvScanInstruction.text = "WRONG TAG!"
                    binding.tvScanInstruction.setTextColor(resources.getColor(R.color.error_red))
                    Toast.makeText(this, "WRONG TAG!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setShowWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) } else { window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD) }
    }

    private fun isDeviceLocked(): Boolean { val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager; return keyguardManager.isKeyguardLocked }
    private fun handleDismissClick() { if (isDeviceLocked()) requestUnlock() }
    private fun requestUnlock() { val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() { override fun onDismissSucceeded() { super.onDismissSucceeded(); updateUIBasedOnLockState() } }) } else { Toast.makeText(this, "Please unlock manually.", Toast.LENGTH_LONG).show() } }

    private fun stopAlarmAndFinish() {
        isAlarmActive = false
        graceTimer?.cancel()
        handler.removeCallbacks(timeRunnable)
        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" }
        startService(stopIntent)
        AlarmScheduler.scheduleAlarm(this)
        finish()
    }
}