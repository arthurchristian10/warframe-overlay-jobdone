package com.warframe.priceoverlay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.warframe.priceoverlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var itemDatabase: ItemDatabase
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_RESULT_DATA", result.data)
            }
            startForegroundService(intent)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("wf_overlay_prefs", MODE_PRIVATE)
        itemDatabase = ItemDatabase(this)
        itemDatabase.load()
        itemDatabase.updateFromApi { Log.d("MainActivity", "DB Status: $it") }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
            } else {
                Toast.makeText(this, "Permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }
}
