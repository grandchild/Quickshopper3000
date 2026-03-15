package org.effekthasch.quickshopper3k.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefsManager: PreferencesManager
    private lateinit var syncServerUriInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        prefsManager = PreferencesManager(this)
        syncServerUriInput = findViewById(R.id.syncServerUriInput)
        syncServerUriInput.setText(prefsManager.syncServerUri ?: "")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        val value = syncServerUriInput.text?.toString()?.trim()
        prefsManager.syncServerUri = if (value.isNullOrBlank()) null else value
    }
}
