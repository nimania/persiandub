package com.persiandub.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.persiandub.mobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("persiandub", MODE_PRIVATE) }
    private lateinit var langCodes: Array<String>

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startDub("internal", result.resultCode, result.data)
            } else {
                setStatus("مجوز ضبط صفحه لغو شد.")
            }
        }

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) continueStart()
            else setStatus("بدون دسترسی ضبط صدا نمی‌توان ادامه داد.")
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        langCodes = resources.getStringArray(R.array.lang_codes)
        val labels = resources.getStringArray(R.array.lang_labels)
        binding.spinnerLang.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        // Restore settings
        binding.etApiKey.setText(prefs.getString("apiKey", ""))
        binding.spinnerLang.setSelection(prefs.getInt("langIndex", 0))
        if (prefs.getString("source", "internal") == "mic") binding.rbMic.isChecked = true
        else binding.rbInternal.isChecked = true

        binding.btnToggle.setOnClickListener { onToggle() }

        // Ask for notification permission up front (non-blocking).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        DubService.statusListener = { s -> runOnUiThread { setStatus(s) } }
        renderRunning(DubService.isRunning)
    }

    override fun onPause() {
        super.onPause()
        DubService.statusListener = null
    }

    private fun onToggle() {
        if (DubService.isRunning) {
            stopService(Intent(this, DubService::class.java))
            renderRunning(false)
            setStatus("در حال توقف…")
            return
        }

        // Save settings
        prefs.edit()
            .putString("apiKey", binding.etApiKey.text.toString().trim())
            .putInt("langIndex", binding.spinnerLang.selectedItemPosition)
            .putString("source", if (binding.rbMic.isChecked) "mic" else "internal")
            .apply()

        if (binding.etApiKey.text.toString().trim().isEmpty()) {
            setStatus("ابتدا کلید Gemini API را وارد کنید.")
            return
        }

        // RECORD_AUDIO is required for BOTH mic and internal (playback) capture.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            continueStart()
        }
    }

    private fun continueStart() {
        if (binding.rbMic.isChecked) {
            startDub("mic", 0, null)
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun startDub(source: String, resultCode: Int, data: Intent?) {
        val intent = Intent(this, DubService::class.java).apply {
            putExtra(DubService.EXTRA_API_KEY, binding.etApiKey.text.toString().trim())
            putExtra(DubService.EXTRA_LANG, langCodes[binding.spinnerLang.selectedItemPosition])
            putExtra(DubService.EXTRA_SOURCE, source)
            putExtra(DubService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(DubService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        renderRunning(true)
        setStatus("در حال شروع…")
    }

    private fun renderRunning(running: Boolean) {
        binding.btnToggle.text = if (running) "توقف دوبله" else "شروع دوبله"
        binding.btnToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, if (running) R.color.red else R.color.teal)
    }

    private fun setStatus(s: String) {
        binding.tvStatus.text = s
    }
}
