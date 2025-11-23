package com.example.fajrbuddy3

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fajrbuddy3.databinding.ActivityWelcomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class WelcomeActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityWelcomeBinding
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var prefs: SharedPreferences
    private var isTagProcessed = false

    // Animators
    private var pulse1Animator: ObjectAnimator? = null
    private var pulse2Animator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("FajrBuddyPrefs", Context.MODE_PRIVATE)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()

        // 1. Check NFC Status immediately on resume
        checkNfcEnabled()

        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            val options = Bundle()
            nfcAdapter?.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options
            )
            startPulseAnimation()
        }
    }

    private fun checkNfcEnabled() {
        if (nfcAdapter == null) {
            binding.tvStatus.text = "❌ Error : NFC Hardware Missing "
            // Optional: Show dialog that app won't work
        } else if (!nfcAdapter!!.isEnabled) {
            showNfcDialog()
        }
    }

    private fun showNfcDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable NFC")
            .setMessage("NFC is required to register your tag.\n\nTip: Keeping NFC turned on consumes zero battery.")
            .setPositiveButton("Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Please enable NFC in settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        stopPulseAnimation()
    }

    private fun startPulseAnimation() {
        if (pulse1Animator?.isRunning == true) return

        // Pulse 1
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)

        pulse1Animator = ObjectAnimator.ofPropertyValuesHolder(binding.viewPulse1, scaleX, scaleY, alpha).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Pulse 2 (Delayed)
        pulse2Animator = ObjectAnimator.ofPropertyValuesHolder(binding.viewPulse2, scaleX, scaleY, alpha).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 750
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulse1Animator?.cancel()
        pulse2Animator?.cancel()
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (isTagProcessed) return

        tag?.let {
            isTagProcessed = true

            val tagId = it.id.joinToString(":") { byte -> "%02x".format(byte) }
            prefs.edit().putString("SAVED_TAG_ID", tagId).apply()

            runOnUiThread {
                stopPulseAnimation()
                binding.tvStatus.text = "Tag Registered Successfully!"
                binding.tvScanInstruction.text = "Complete."
                binding.imgNfcIcon.setColorFilter(android.graphics.Color.GREEN)

                Toast.makeText(this, "Tag Registered!", Toast.LENGTH_SHORT).show()

                // Delay transition
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    finish()
                }, 800)
            }
        }
    }
}