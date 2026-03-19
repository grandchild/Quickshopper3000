package org.effekthasch.quickshopper3k.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.MainActivity
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.DialogAddItemBinding
import org.effekthasch.quickshopper3k.databinding.DialogItemContextMenuBinding
import org.effekthasch.quickshopper3k.databinding.DialogImportItemsBinding
import org.effekthasch.quickshopper3k.databinding.DialogImportItemsPreviewBinding
import org.effekthasch.quickshopper3k.databinding.FragmentMainBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.FabController
import org.effekthasch.quickshopper3k.util.BackupManager
import org.effekthasch.quickshopper3k.util.DoneVisibilityMode
import org.effekthasch.quickshopper3k.util.PreferencesManager

class MainFragment :
    org.effekthasch.quickshopper3k.ui.common.BaseListFragment<StoreItem>(),
    FabController {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var prefsManager: PreferencesManager

    private var currentStoreName: String? = null
    private var lastKeyboardVisible = false
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImportBackup(it) }
    }

    override fun provideAdapter() = MainItemsAdapter(
        onItemClick = {
            if (binding.searchContainer.visibility == View.VISIBLE) {
                dismissSearch()
            }
            false
        },
        onItemLongClick = { storeItem, rawX, rawY ->
            if (binding.searchContainer.visibility == View.VISIBLE) {
                dismissSearch()
            } else {
                showItemContextMenu(storeItem, rawX, rawY)
            }
        },
        onStartDrag = { holder -> enableDragOn(holder) }
    )

    override fun onSwipeLeft(item: StoreItem) {
        viewModel.handleSwipeLeft(item.item)
        reselectSearchAfterSwipe()
    }

    override fun onSwipeRight(item: StoreItem) {
        viewModel.handleSwipeRight(item.item)
        reselectSearchAfterSwipe()
    }

    private fun reselectSearchAfterSwipe() {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            binding.searchInput.post {
                binding.searchInput.requestFocus()
                binding.searchInput.selectAll()
                val imm = getSystemService(requireContext(), InputMethodManager::class.java)
                imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun persistOrder(items: List<StoreItem>) {
        viewModel.updateItemOrderFromList(items) { result ->
            result.onFailure {
                Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBottomBarTextChanged(text: String) {
        viewModel.setSearchQuery(text)
        updateAddButtonVisibility(text)
    }

    override fun bottomBarImeAction(): Int = EditorInfo.IME_ACTION_DONE

    override fun getSwipeRightTargetState(item: StoreItem): String {
        return when (item.item.state) {
            ItemState.ACTIVATED -> "Done"
            ItemState.DEACTIVATED -> "Needed"
            ItemState.POSTPONED -> "Needed"
        }
    }

    override fun getSwipeLeftTargetState(item: StoreItem): String {
        return when (item.item.state) {
            ItemState.ACTIVATED -> "N/A"
            ItemState.DEACTIVATED -> "N/A"
            ItemState.POSTPONED -> "Done"
        }
    }

    override fun onFabClick() {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            dismissSearch()
        } else {
            showInlineSearch()
        }
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_import_items -> {
                showImportItemsDialog()
                true
            }

            R.id.action_import_backup -> {
                importBackupLauncher.launch("*/*")
                true
            }

            R.id.action_export_backup -> {
                exportBackup()
                true
            }

            R.id.action_activate_all -> {
                viewModel.activateAll { result ->
                    result.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }

            R.id.action_deactivate_all -> {
                viewModel.deactivateAll { result ->
                    result.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }

            R.id.action_flush_recently_done -> {
                viewModel.flushRecentlyDone()
                prefsManager.flushRecentlyDoneAt = System.currentTimeMillis()
                true
            }

            R.id.action_share_active_items -> {
                shareActiveItems()
                true
            }

            else -> false
        }
    }

    override fun onMenuItemSelected(itemId: Int, menuItem: android.view.MenuItem): Boolean {
        return onMenuItemSelected(itemId)
    }

    override fun updateFabIcon(iconRes: Int, contentDescription: String) {
    }

    override fun showFab() {
    }

    override fun hideFab() {
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(repository)
        )[MainViewModel::class.java]

        val initialStoreId = prefsManager.selectedShopStoreIds.firstOrNull() ?: 0L
        viewModel.setCurrentStore(initialStoreId)
        viewModel.setDoneVisibilityMode(prefsManager.doneVisibilityMode)
        viewModel.setRecentDoneMinutes(prefsManager.recentDoneMinutes)
        viewModel.setHistoryDays(prefsManager.historyDays)
        viewModel.setMistakeDoneSeconds(prefsManager.mistakeDoneSeconds)
        viewModel.setReactivateMinSeconds(prefsManager.reactivateMinSeconds)
        viewModel.setFlushRecentlyDoneAt(prefsManager.flushRecentlyDoneAt)

        setupRecyclerView(binding.itemsRecyclerView)
        setupBottomBar(binding.searchContainer, binding.searchInput)
        setupInlineSearch()
        setupAddButton()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        activity?.invalidateOptionsMenu()
        (activity as? MainActivity)?.updateStoreSelectorButton()
        (activity as? MainActivity)?.updateDoneVisibilityButton()
    }

    private fun setupAddButton() {
        binding.addItemButton.setOnClickListener {
            addCurrentSearchAsItem()
        }
    }

    private fun updateAddButtonVisibility(query: String) {
        val showButton = query.isNotBlank() && !viewModel.hasExactMatchRaw(query)
        binding.addItemButton.visibility = if (showButton) View.VISIBLE else View.GONE

        val newImeAction = if (showButton) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_SEARCH
        if ((binding.searchInput.imeOptions and EditorInfo.IME_MASK_ACTION) != newImeAction) {
            binding.searchInput.imeOptions = newImeAction
        }
    }

    private fun addCurrentSearchAsItem() {
        val name = binding.searchInput.text?.toString()?.trim() ?: ""
        if (name.isNotBlank()) {
            viewModel.addItem(name) { result ->
                result.onSuccess {
                    binding.searchInput.text?.clear()
                    viewModel.setSearchQuery("")
                }.onFailure {
                    Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupInlineSearch() {


        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_DONE -> {
                    val query = binding.searchInput.text?.toString()?.trim() ?: ""
                    if (query.isNotBlank() && !viewModel.hasExactMatch(query)) {
                        addCurrentSearchAsItem()
                    } else {
                        dismissSearch()
                    }
                    true
                }

                EditorInfo.IME_ACTION_SEARCH -> {
                    dismissSearch()
                    true
                }

                else -> false
            }
        }

        binding.searchInput.setOnFocusChangeListener { _, _ -> }

        binding.itemsRecyclerView.setOnTouchListener { view, event ->
            if (binding.searchContainer.visibility == View.VISIBLE &&
                event.action == android.view.MotionEvent.ACTION_DOWN
            ) {
                dismissSearch()
                view.performClick()
                true
            } else {
                false
            }
        }

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                viewModel.setSearchQuery(text)
                updateAddButtonVisibility(text)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun startKeyboardDismissListener() {
        if (keyboardListener != null) return
        val rootView = binding.root
        lastKeyboardVisible = isKeyboardVisible(rootView)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val keyboardVisible = isKeyboardVisible(rootView)
            if (!keyboardVisible && lastKeyboardVisible && binding.searchContainer.visibility == View.VISIBLE) {
                dismissSearch()
            }
            lastKeyboardVisible = keyboardVisible
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        keyboardListener = listener
    }

    private fun stopKeyboardDismissListener() {
        val listener = keyboardListener ?: return
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        keyboardListener = null
    }

    private fun isKeyboardVisible(rootView: View): Boolean {
        val r = Rect()
        rootView.getWindowVisibleDisplayFrame(r)
        val screenHeight = rootView.rootView.height
        val keyboardHeight = screenHeight - r.height()
        return keyboardHeight > screenHeight * 0.15
    }

    private fun observeData() {
        viewModel.filteredItems.observe(viewLifecycleOwner) { items ->
            applyItems(items)
            updateSearchActiveBar()
            val query = viewModel.searchQuery.value ?: ""
            updateAddButtonVisibility(query)
        }

        viewModel.stores.observe(viewLifecycleOwner) { _ ->
            updateStoreMenuTitle()
            (activity as? MainActivity)?.updateStoreSelectorButton()
        }

        viewModel.currentStoreId.observe(viewLifecycleOwner) { storeId ->
            viewModel.stores.value?.let { stores ->
                currentStoreName = stores.find { it.id == storeId }?.name
                updateStoreMenuTitle()
                (activity as? MainActivity)?.updateStoreSelectorButton()
            }
        }

        viewModel.doneVisibilityMode.observe(viewLifecycleOwner) {
            (activity as? MainActivity)?.updateDoneVisibilityButton()
        }
    }

    fun cycleDoneVisibilityMode() {
        viewModel.cycleDoneVisibilityMode()
        prefsManager.doneVisibilityMode = viewModel.doneVisibilityMode.value ?: DoneVisibilityMode.RECENT
    }

    fun getDoneVisibilityMode(): DoneVisibilityMode {
        return viewModel.doneVisibilityMode.value ?: DoneVisibilityMode.RECENT
    }

    private fun updateStoreMenuTitle() {
        (activity as? MainActivity)?.invalidateOptionsMenu()
        activity?.invalidateOptionsMenu()
    }

    fun showStoreSelectionDropdown(menuItem: android.view.MenuItem?) {
        val stores = viewModel.stores.value ?: return
        val allStores = listOf(
            org.effekthasch.quickshopper3k.domain.model.Store(0L, "", "All items", false)
        ) + stores

        val anchorView = menuItem?.actionView
            ?: (activity as? MainActivity)?.findViewById<android.view.View>(R.id.storeSelectorButton)
            ?: return

        val popupMenu = android.widget.PopupMenu(requireContext(), anchorView)

        allStores.forEachIndexed { index, store ->
            val displayName = if (store.id == 0L) {
                store.name
            } else {
                val prefix = if (store.specialtyStore) "★ " else ""
                prefix + store.name
            }
            popupMenu.menu.add(0, index, index, displayName)
        }

        popupMenu.setOnMenuItemClickListener { item ->
            val selectedStore = allStores[item.itemId]
            viewModel.setCurrentStore(selectedStore.id)
            prefsManager.selectedShopStoreIds = setOf(selectedStore.id)
            true
        }

        popupMenu.show()
    }

    fun getStoreMenuTitle(): String {
        val currentStoreId = viewModel.currentStoreId.value
        val stores = viewModel.stores.value
        val indicator = getString(R.string.store_indicator)

        if (currentStoreId == null || stores == null) {
            return indicator
        }

        if (currentStoreId == 0L) {
            return indicator
        }

        val store = stores.find { it.id == currentStoreId }
        return store?.name ?: indicator
    }

    private fun updateSearchActiveBar() {
        val q = viewModel.searchQuery.value ?: ""
        val barVisible = q.isNotBlank() && binding.searchContainer.visibility != View.VISIBLE
        binding.searchActiveBar.visibility = if (barVisible) View.VISIBLE else View.GONE
        binding.searchActiveText.text = q
        binding.searchActiveClear.setOnClickListener {
            viewModel.setSearchQuery("")
        }
        binding.searchActiveText.setOnClickListener {
            showInlineSearch()
        }
    }

    private fun showInlineSearch() {
        binding.searchContainer.visibility = View.VISIBLE
        binding.searchInput.setText(viewModel.searchQuery.value ?: "")
        binding.searchInput.requestFocus()
        binding.searchInput.selectAll()
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        startKeyboardDismissListener()
        (activity as? MainActivity)?.hideBottomBar()
        updateAddButtonVisibility(viewModel.searchQuery.value ?: "")
    }

    private fun hideInlineSearch() {
        binding.searchContainer.visibility = View.GONE
        binding.addItemButton.visibility = View.GONE
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        stopKeyboardDismissListener()
        (activity as? MainActivity)?.showBottomBar()
    }

    private fun dismissSearch() {
        viewModel.setSearchQuery("")
        binding.searchInput.setText("")
        binding.searchInput.clearFocus()
        hideInlineSearch()
        updateSearchActiveBar()
    }

    override fun onBackPressed(): Boolean {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            dismissSearch()
            return true
        }
        return false
    }

    private fun showItemContextMenu(storeItem: StoreItem, rawX: Float, rawY: Float) {
        val menuBinding = DialogItemContextMenuBinding.inflate(layoutInflater)

        val storeSelected = viewModel.isStoreSelected()
        if (storeSelected) {
            menuBinding.contextMenuUnavailable.text = if (storeItem.isAvailable) {
                getString(R.string.context_menu_unavailable)
            } else {
                getString(R.string.context_menu_available)
            }
            menuBinding.contextMenuUnavailable.visibility = View.VISIBLE
        } else {
            menuBinding.contextMenuUnavailable.visibility = View.GONE
        }

        var currentAmount = storeItem.item.amount

        fun updateAmountUi() {
            if (currentAmount > 1) {
                menuBinding.contextMenuAmountMinus.visibility = View.VISIBLE
                menuBinding.contextMenuAmountValue.visibility = View.VISIBLE
                menuBinding.contextMenuAmountValue.text = currentAmount.toString()
            } else {
                menuBinding.contextMenuAmountMinus.visibility = View.INVISIBLE
                menuBinding.contextMenuAmountValue.visibility = View.INVISIBLE
            }
        }

        updateAmountUi()

        menuBinding.contextMenuAmountPlus.setOnClickListener {
            currentAmount++
            updateAmountUi()
            viewModel.updateItemAmount(storeItem.item.id, currentAmount) { result ->
                result.onFailure {
                    Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        menuBinding.contextMenuAmountMinus.setOnClickListener {
            if (currentAmount > 1) {
                currentAmount--
                updateAmountUi()
                viewModel.updateItemAmount(storeItem.item.id, currentAmount) { result ->
                    result.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val popupView = menuBinding.root
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f
        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                android.util.TypedValue().let { tv ->
                    requireContext().theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
                    tv.data
                }
            ))

        val anchor = binding.root
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        val screenHeight = resources.displayMetrics.heightPixels
        val menuHeight = popupView.measuredHeight
        val fitsBelow = rawY + menuHeight <= screenHeight

        val x = (rawX - anchorLocation[0] - popupView.measuredWidth / 2).toInt()
        val y = if (fitsBelow) {
            (rawY - anchorLocation[1]).toInt()
        } else {
            (rawY - anchorLocation[1] - menuHeight).toInt()
        }

        menuBinding.contextMenuEdit.setOnClickListener {
            popup.dismiss()
            showEditItemDialog(storeItem)
        }

        menuBinding.contextMenuUnavailable.setOnClickListener {
            popup.dismiss()
            viewModel.toggleItemAvailability(storeItem.item.id, storeItem.isAvailable) { result ->
                result.onFailure {
                    Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        popup.showAsDropDown(anchor, x, y, Gravity.NO_GRAVITY)
    }

    private fun showEditItemDialog(storeItem: StoreItem) {
        val dialogBinding = DialogAddItemBinding.inflate(layoutInflater)
        dialogBinding.itemNameInput.setText(storeItem.item.name)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_item_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = dialogBinding.itemNameInput.text?.toString() ?: ""
                if (newName.isNotBlank()) {
                    viewModel.updateItemName(storeItem.item.id, newName) { result ->
                        result.onFailure {
                            Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.error_empty_name, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                viewModel.deleteItem(storeItem.item.id) { result ->
                    result.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialogBinding.itemNameInput.requestFocus()
    }

    private fun showImportItemsDialog() {
        val dialogBinding = DialogImportItemsBinding.inflate(layoutInflater)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_items_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = dialogBinding.importTextInput.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    val progressDialog = AlertDialog.Builder(requireContext())
                        .setMessage(R.string.importing_items)
                        .setCancelable(false)
                        .create()
                    progressDialog.show()

                    viewModel.getImportPreview(text) { result ->
                        progressDialog.dismiss()
                        result.onSuccess { preview ->
                            if (preview.existingItems.isEmpty()) {
                                importNewItems(preview.newItems)
                                return@onSuccess
                            }
                            showImportItemsPreviewDialog(preview)
                        }.onFailure {
                            Snackbar.make(
                                binding.root,
                                R.string.error_occurred,
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialogBinding.importTextInput.requestFocus()
    }

    private fun showImportItemsPreviewDialog(preview: MainViewModel.ImportPreview) {
        val dialogBinding = DialogImportItemsPreviewBinding.inflate(layoutInflater)
        dialogBinding.importItemsText.text = buildImportPreviewText(preview)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_preview_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.import_preview_import_only) { _, _ ->
                importNewItems(preview.newItems)
            }
            .setNegativeButton(R.string.import_preview_import_activate_all) { _, _ ->
                importAndActivateItems(preview.orderedItems.map { it.name }, preview.newItems)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun buildImportPreviewText(preview: MainViewModel.ImportPreview): CharSequence {
        val existingColor = ContextCompat.getColor(requireContext(), R.color.import_preview_existing)
        val newColor = ContextCompat.getColor(requireContext(), R.color.import_preview_new)
        val builder = SpannableStringBuilder()

        preview.orderedItems.forEachIndexed { index, item ->
            val start = builder.length
            builder.append(item.name)
            val end = builder.length
            val color = if (item.isExisting) existingColor else newColor
            builder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < preview.orderedItems.lastIndex) {
                builder.append("\n")
            }
        }

        return builder
    }

    private fun importAndActivateItems(allNames: List<String>, newNames: List<String>) {
        if (allNames.isEmpty()) {
            return
        }
        if (newNames.isEmpty()) {
            activateImportedItems(allNames)
            return
        }

        importNewItems(newNames) { result ->
            result.onSuccess {
                activateImportedItems(allNames)
            }
        }
    }

    private fun activateImportedItems(names: List<String>) {
        viewModel.activateItemsByNames(names) { result ->
            result.onFailure {
                Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun importNewItems(names: List<String>, onResult: ((Result<Int>) -> Unit)? = null) {
        if (names.isEmpty()) {
            Snackbar.make(
                binding.root,
                resources.getQuantityString(R.plurals.items_imported, 0, 0),
                Snackbar.LENGTH_SHORT
            ).show()
            onResult?.invoke(Result.success(0))
            return
        }

        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.importing_items)
            .setCancelable(false)
            .create()
        progressDialog.show()

        viewModel.importItems(names) { result ->
            progressDialog.dismiss()
            result.onSuccess { count ->
                Snackbar.make(
                    binding.root,
                    resources.getQuantityString(R.plurals.items_imported, count, count),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure {
                Snackbar.make(
                    binding.root,
                    R.string.error_occurred,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            onResult?.invoke(result)
        }
    }

    private fun shareActiveItems() {
        val items = viewModel.filteredItems.value.orEmpty()
            .filter { it.item.state == ItemState.ACTIVATED }

        if (items.isEmpty()) {
            Snackbar.make(binding.root, R.string.share_active_items_empty, Snackbar.LENGTH_SHORT).show()
            return
        }

        val text = items.joinToString(separator = "\n") { it.item.name }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_active_items)))
    }

    private fun exportBackup() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.exporting_backup)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val backupManager = BackupManager(requireContext())
            val result = backupManager.exportBackup()

            progressDialog.dismiss()

            result.onSuccess { backupFile ->
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    backupFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.export_backup)))

                Snackbar.make(binding.root, R.string.backup_exported, Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                Snackbar.make(binding.root, R.string.error_backup_export, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportBackup(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_backup_title)
            .setMessage(R.string.import_backup_warning)
            .setPositiveButton(R.string.ok) { _, _ ->
                performImportBackup(uri)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performImportBackup(uri: Uri) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.importing_backup)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val tempFile = java.io.File(requireContext().cacheDir, "temp_backup.zip")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val backupManager = BackupManager(requireContext())
                val result = backupManager.importBackup(tempFile)

                tempFile.delete()
                progressDialog.dismiss()

                result.onSuccess {
                    Snackbar.make(binding.root, R.string.backup_imported, Snackbar.LENGTH_LONG).show()
                    requireActivity().recreate()
                }.onFailure { error ->
                    if (error is BackupManager.LegacyBackupException) {
                        Snackbar.make(
                            binding.root,
                            R.string.error_legacy_backup,
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        val message = if (error is IllegalArgumentException) {
                            R.string.error_invalid_backup
                        } else {
                            R.string.error_backup_import
                        }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Snackbar.make(binding.root, R.string.error_backup_import, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        stopKeyboardDismissListener()
        super.onDestroyView()
        _binding = null
    }
}
