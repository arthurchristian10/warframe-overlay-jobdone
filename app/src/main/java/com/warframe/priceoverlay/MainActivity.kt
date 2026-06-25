package com.warframe.priceoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.warframe.priceoverlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Service is already running (started before the permission prompt).
            // Just send it the projection result via a regular startService().
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // On Android 13+ putExtra with a Parcelable Intent works fine
                    putExtra("EXTRA_RESULT_DATA", result.data)
                } else {
                    putExtra("EXTRA_RESULT_DATA", result.data)
                }
            }
            startService(intent) // Service already running — no need for startForegroundService
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
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

            // Android 14 requirement: the mediaProjection foreground service MUST be
            // already running BEFORE the screen capture permission dialog is shown.
            // So we start the service first, then request the permission.
            val serviceIntent = Intent(this, OverlayService::class.java)
            startForegroundService(serviceIntent)

            // Small delay to let the service start and call startForeground()
            // before the permission dialog appears
            binding.btnToggleOverlay.postDelayed({
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }, 300)
        }
    }
}
