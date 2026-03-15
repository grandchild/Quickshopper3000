package org.effekthasch.quickshopper3k.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.util.Ulid
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.local.SyncStateEntity
import org.effekthasch.quickshopper3k.data.sync.IntentApplier
import org.effekthasch.quickshopper3k.data.sync.SyncHttpClient
import org.effekthasch.quickshopper3k.data.sync.SyncIntentSerializer
import org.effekthasch.quickshopper3k.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefsManager: PreferencesManager
    private lateinit var syncServerUriInput: TextInputEditText
    private lateinit var joinListInput: TextInputEditText
    private lateinit var joinListInputLayout: TextInputLayout
    private var validateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        prefsManager = PreferencesManager(this)
        syncServerUriInput = findViewById(R.id.syncServerUriInput)
        syncServerUriInput.setText(prefsManager.syncServerUri ?: "")

        val listIdDisplay = findViewById<TextView>(R.id.listIdDisplay)
        listIdDisplay.text = getString(R.string.list_id_label, prefsManager.listId)
        listIdDisplay.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prefsManager.listId)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_list)))
        }

        joinListInput = findViewById(R.id.joinListInput)
        joinListInputLayout = findViewById(R.id.joinListInputLayout)
        setupJoinInput()

        findViewById<MaterialButton>(R.id.resetListIdButton).setOnClickListener {
            lifecycleScope.launch { resetListId() }
        }
    }

    private suspend fun resetListId() {
        val confirmed = showConfirmDialog(
            R.string.reset_list_id_title,
            R.string.reset_list_id_warning
        )
        if (!confirmed) return

        val db = AppDatabase.getDatabase(this)
        withContext(Dispatchers.IO) {
            db.syncDao().purgeAll()
            db.syncDao().deleteSyncState("last_synced_ulid")
        }

        prefsManager.listId = Ulid.generate()

        findViewById<TextView>(R.id.listIdDisplay).text =
            getString(R.string.list_id_label, prefsManager.listId)
        Toast.makeText(this, R.string.list_id_reset, Toast.LENGTH_SHORT).show()
    }

    private fun setupJoinInput() {
        joinListInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                joinListInputLayout.error = null
                validateJob?.cancel()
                val text = s?.toString()?.trim() ?: return
                if (text.isEmpty()) return
                if (!Ulid.isValid(text)) {
                    joinListInputLayout.error = getString(R.string.error_invalid_list_id)
                    return
                }
                if (text == prefsManager.listId) {
                    joinListInputLayout.error = getString(R.string.error_same_list)
                    return
                }
                validateJob = lifecycleScope.launch {
                    delay(500)
                    validateAndJoin(text)
                }
            }
        })
    }

    private suspend fun validateAndJoin(listId: String) {
        val baseUrl = prefsManager.syncServerUri
        if (baseUrl == null) {
            joinListInputLayout.error = getString(R.string.error_no_server)
            return
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val client = SyncHttpClient(baseUrl)
                client.getIntents(listId, null).getOrThrow()
            }
        }

        if (result.isFailure) {
            joinListInputLayout.error = getString(R.string.error_invalid_list_id)
            return
        }

        val intents = result.getOrNull() ?: return

        val confirmed = showConfirmDialog(
            R.string.join_list_title,
            R.string.join_list_warning
        )
        if (!confirmed) {
            joinListInput.text?.clear()
            return
        }

        val applyResult = withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getDatabase(this@SettingsActivity)
                val applier = IntentApplier(db.itemDao(), db.storeDao(), db.syncDao())
                for (i in 0 until intents.length()) {
                    val json = intents.getJSONObject(i)
                    val envelope = SyncIntentSerializer.fromJson(json) ?: continue
                    val serverUlid = envelope.serverUlid ?: continue
                    applier.apply(envelope.intent, serverUlid)
                }
                prefsManager.listId = listId
            }
        }

        if (applyResult.isSuccess) {
            joinListInput.text?.clear()
            findViewById<TextView>(R.id.listIdDisplay).text =
                getString(R.string.list_id_label, listId)
            Toast.makeText(this, R.string.joined_list, Toast.LENGTH_SHORT).show()
        } else {
            joinListInputLayout.error = getString(R.string.error_join_list)
        }
    }

    private suspend fun showConfirmDialog(titleRes: Int, messageRes: Int): Boolean {
        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(titleRes)
                    .setMessage(messageRes)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resume(true) {}
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false) {}
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false) {}
                    }
                    .show()
            }
        }
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
