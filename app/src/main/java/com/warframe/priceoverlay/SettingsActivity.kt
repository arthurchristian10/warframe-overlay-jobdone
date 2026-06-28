package com.warframe.priceoverlay

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("wf_overlay_prefs", Context.MODE_PRIVATE)

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val switchCapture = findViewById<SwitchCompat>(R.id.switch_capture)

        btnBack.setOnClickListener { finish() }

        switchCapture.isChecked = prefs.getBoolean("allow_captures", false)
        switchCapture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("allow_captures", isChecked).apply()
            Toast.makeText(this, "Restart overlay to apply changes", Toast.LENGTH_SHORT).show()
        }
    }
}
