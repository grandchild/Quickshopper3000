package org.effekthasch.quickshopper3k.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.button.MaterialButton
import org.effekthasch.quickshopper3k.ui.DebugActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.effekthasch.quickshopper3k.MainActivity
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.util.Ulid
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ExampleItemsSeeder

import org.effekthasch.quickshopper3k.data.sync.IntentApplier
import org.effekthasch.quickshopper3k.data.sync.SyncManager
import org.effekthasch.quickshopper3k.data.sync.SyncHttpClient
import org.effekthasch.quickshopper3k.data.sync.SyncIntentSerializer
import org.effekthasch.quickshopper3k.util.BackupManager
import org.effekthasch.quickshopper3k.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    data class PendingJoin(val serverUrl: String, val listId: String)

    private lateinit var prefsManager: PreferencesManager
    private lateinit var syncServerUriInput: TextInputEditText
    private lateinit var joinListInput: TextInputEditText
    private lateinit var joinListInputLayout: TextInputLayout
    private var validateJob: Job? = null
    private var pendingJoin: PendingJoin? = null

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImportBackup(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val versionText = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            getString(R.string.app_version_debug, getString(R.string.app_name), versionName)
        } else {
            getString(R.string.app_version, getString(R.string.app_name), versionName)
        }
        findViewById<TextView>(R.id.versionText).text = versionText

        prefsManager = PreferencesManager(this)
        syncServerUriInput = findViewById(R.id.syncServerUriInput)
        syncServerUriInput.setText(prefsManager.syncServerUri ?: "")

        val listIdDisplay = findViewById<TextView>(R.id.listIdDisplay)
        listIdDisplay.text = getString(R.string.list_id_label, prefsManager.listId)
        listIdDisplay.setOnClickListener {
            val serverUri = prefsManager.syncServerUri ?: return@setOnClickListener
            val listId = prefsManager.listId
            val link = when {
                serverUri.startsWith("https://") ->
                    "qs3ks://" + serverUri.removePrefix("https://").trimEnd('/') + "/$listId"
                serverUri.startsWith("http://") ->
                    "qs3k://" + serverUri.removePrefix("http://").trimEnd('/') + "/$listId"
                else -> return@setOnClickListener
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_list)))
        }

        joinListInput = findViewById(R.id.joinListInput)
        joinListInputLayout = findViewById(R.id.joinListInputLayout)
        setupJoinInput()

        findViewById<MaterialButton>(R.id.debugButton).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.resetListIdButton).setOnClickListener {
            lifecycleScope.launch { resetListId() }
        }

        findViewById<MaterialButton>(R.id.exportBackupButton).setOnClickListener {
            exportBackup()
        }

        findViewById<MaterialButton>(R.id.importBackupButton).setOnClickListener {
            importBackupLauncher.launch("*/*")
        }

        intent?.data?.toString()?.let { uriStr ->
            parsePendingJoin(uriStr, null)?.let { pj ->
                pendingJoin = pj
                lifecycleScope.launch { executeJoin(pj) }
            }
        }
    }

    private fun parsePendingJoin(input: String, fallbackServerUrl: String?): PendingJoin? {
        val trimmed = input.trim()
        if (trimmed.startsWith("qs3ks://") || trimmed.startsWith("qs3k://")) {
            val secure = trimmed.startsWith("qs3ks://")
            val scheme = if (secure) "qs3ks://" else "qs3k://"
            val rest = trimmed.removePrefix(scheme).trimEnd('/')
            val lastSlash = rest.lastIndexOf('/')
            if (lastSlash < 0) return null
            val listId = rest.substring(lastSlash + 1)
            if (!Ulid.isValid(listId)) return null
            val hostAndPath = rest.substring(0, lastSlash)
            val serverUrl = (if (secure) "https://" else "http://") + hostAndPath
            return PendingJoin(serverUrl, listId)
        }
        if (Ulid.isValid(trimmed)) {
            val server = fallbackServerUrl ?: return null
            if (server.isBlank()) return null
            return PendingJoin(server, trimmed)
        }
        return null
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

                val fallback = syncServerUriInput.text?.toString()?.trim()
                val parsed = parsePendingJoin(text, fallback)
                if (parsed == null) {
                    joinListInputLayout.error = getString(R.string.error_invalid_list_id)
                    return
                }
                if (parsed.listId == prefsManager.listId) {
                    joinListInputLayout.error = getString(R.string.error_same_list)
                    return
                }
                validateJob = lifecycleScope.launch {
                    delay(500)
                    pendingJoin = parsed
                    executeJoin(parsed)
                }
            }
        })
    }

    private suspend fun executeJoin(pending: PendingJoin) {
        val serverResult = withContext(Dispatchers.IO) {
            runCatching {
                val client = SyncHttpClient(pending.serverUrl)
                val httpResult = client.getIntents(
                    pending.listId,
                    null,
                    prefsManager.deviceId,
                ).getOrThrow()
                val body = httpResult.body
                when (val parsed = org.json.JSONTokener(body).nextValue()) {
                    is org.json.JSONObject -> parsed.getJSONArray("intents")
                    is org.json.JSONArray -> parsed
                    else -> throw IllegalArgumentException("Unexpected join response")
                }
            }
        }

        if (serverResult.isFailure) {
            showNoSuchListModal(pending)
            return
        }

        val intents = serverResult.getOrNull()
        if (intents == null) {
            showNoSuchListModal(pending)
            return
        }

        val isEmpty = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@SettingsActivity)
            db.itemDao().getAllItems().isEmpty() && db.storeDao().getAllStoresList().isEmpty()
        }

        if (isEmpty) {
            applyJoin(pending, intents)
            return
        }

        val confirmed = showJoinOptionsDialog(listExists = true)
        if (confirmed == JoinAction.CANCEL) {
            joinListInput.text?.clear()
            pendingJoin = null
            return
        }

        if (confirmed == JoinAction.BACKUP_AND_JOIN) {
            BackupManager(this).exportBackup()
        }

        applyJoin(pending, intents)
    }

    private suspend fun applyJoin(pending: PendingJoin, intents: org.json.JSONArray) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getDatabase(this@SettingsActivity)
                db.withTransaction {
                    db.clearAllTables()
                    val applier = IntentApplier(
                        db.itemDao(),
                        db.storeDao(),
                        db.syncDao(),
                        { prefsManager.historyDays },
                        { prefsManager.mistakeDoneSeconds },
                        { prefsManager.reactivateMinSeconds }
                    )
                    for (i in 0 until intents.length()) {
                        val json = intents.getJSONObject(i)
                        val envelope = SyncIntentSerializer.fromJson(json) ?: continue
                        val serverUlid = envelope.serverUlid ?: continue
                        applier.apply(envelope.intent, serverUlid)
                    }
                }
            }
        }
        if (result.isSuccess) {
            prefsManager.syncServerUri = pending.serverUrl
            prefsManager.listId = pending.listId
            prefsManager.exampleItemsInitialized = true
            prefsManager.flushRecentlyDoneAt = System.currentTimeMillis()
            val synced = syncCurrentList()
            prefsManager.flushRecentlyDoneAt = System.currentTimeMillis()
            syncServerUriInput.setText(pending.serverUrl)
            joinListInput.text?.clear()
            findViewById<TextView>(R.id.listIdDisplay).text =
                getString(R.string.list_id_label, pending.listId)
            Toast.makeText(this, R.string.joined_list, Toast.LENGTH_SHORT).show()
            if (!synced) {
                Toast.makeText(this, R.string.error_occurred, Toast.LENGTH_SHORT).show()
            }
        } else {
            joinListInputLayout.error = getString(R.string.error_join_list)
        }
        pendingJoin = null
    }

    private suspend fun syncCurrentList(): Boolean {
        return withContext(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(this@SettingsActivity)
            SyncManager(
                database = database,
                serverUrlProvider = { prefsManager.syncServerUri },
                deviceIdProvider = { prefsManager.deviceId },
                listIdProvider = { prefsManager.listId },
                initializeEmptyList = {
                    if (!prefsManager.exampleItemsInitialized) {
                        ExampleItemsSeeder(database).seedIfEmpty()
                        prefsManager.exampleItemsInitialized = true
                    }
                },
                historyDaysProvider = { prefsManager.historyDays },
                mistakeDoneSecondsProvider = { prefsManager.mistakeDoneSeconds },
                reactivateMinSecondsProvider = { prefsManager.reactivateMinSeconds }
            ).syncNow()
        }
    }

    private fun restartMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
    }

    private fun showNoSuchListModal(pending: PendingJoin) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.join_list_title)
                .setMessage("No such list exists on this server. You can create it.")
                .setPositiveButton("Create") { _, _ ->
                    lifecycleScope.launch {
                        val db = AppDatabase.getDatabase(this@SettingsActivity)
                        withContext(Dispatchers.IO) {
                            db.syncDao().purgeAll()
                            db.syncDao().deleteSyncState("last_synced_ulid")
                        }
                        prefsManager.syncServerUri = pending.serverUrl
                        prefsManager.listId = pending.listId
                        syncServerUriInput.setText(pending.serverUrl)
                        joinListInput.text?.clear()
                        findViewById<TextView>(R.id.listIdDisplay).text =
                            getString(R.string.list_id_label, pending.listId)
                        Toast.makeText(this@SettingsActivity, R.string.joined_list, Toast.LENGTH_SHORT).show()
                        pendingJoin = null
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> pendingJoin = null }
                .show()
        }
    }

    private enum class JoinAction { CANCEL, BACKUP_AND_JOIN, JOIN_WITHOUT_BACKUP }

    private suspend fun showJoinOptionsDialog(listExists: Boolean): JoinAction = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val builder = AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.join_list_title)
                .setMessage(
                    if (listExists)
                        "You have local data. What do you want to do?"
                    else
                        "No such list exists. You can create it, but you have local data. What do you want to do?"
                )
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(JoinAction.CANCEL) {}
                }
                .setNeutralButton(
                    if (listExists) "Join without backup" else "Create without backup"
                ) { _, _ ->
                    if (cont.isActive) cont.resume(JoinAction.JOIN_WITHOUT_BACKUP) {}
                }
                .setPositiveButton(
                    if (listExists) "Backup and join" else "Backup and create"
                ) { _, _ ->
                    if (cont.isActive) cont.resume(JoinAction.BACKUP_AND_JOIN) {}
                }
            builder.setOnCancelListener {
                if (cont.isActive) cont.resume(JoinAction.CANCEL) {}
            }
            builder.show()
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

    private fun exportBackup() {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.exporting_backup)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val backupManager = BackupManager(this@SettingsActivity)
            val result = backupManager.exportBackup()

            progressDialog.dismiss()

            result.onSuccess { backupFile ->
                val uri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${packageName}.fileprovider",
                    backupFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.export_backup)))
            }.onFailure {
                Toast.makeText(this@SettingsActivity, R.string.error_backup_export, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportBackup(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_backup_title)
            .setMessage(R.string.import_backup_warning)
            .setPositiveButton(R.string.ok) { _, _ -> performImportBackup(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performImportBackup(uri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.importing_backup)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val tempFile = java.io.File(cacheDir, "temp_backup.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                val backupManager = BackupManager(this@SettingsActivity)
                val result = backupManager.importBackup(tempFile)

                tempFile.delete()
                progressDialog.dismiss()

                result.onSuccess {
                    prefsManager.flushRecentlyDoneAt = System.currentTimeMillis()
                    val synced = syncCurrentList()
                    prefsManager.flushRecentlyDoneAt = System.currentTimeMillis()
                    if (synced) {
                        Toast.makeText(this@SettingsActivity, R.string.backup_imported, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, R.string.error_occurred, Toast.LENGTH_LONG).show()
                    }
                    restartMainActivity()
                }.onFailure { error ->
                    val message = when {
                        error is BackupManager.LegacyBackupException -> R.string.error_legacy_backup
                        error is IllegalArgumentException -> R.string.error_invalid_backup
                        else -> R.string.error_backup_import
                    }
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@SettingsActivity, R.string.error_backup_import, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        if (pendingJoin != null) return
        val value = syncServerUriInput.text?.toString()?.trim()
        prefsManager.syncServerUri = if (value.isNullOrBlank()) null else value
    }
}
