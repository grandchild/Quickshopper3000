package org.effekthasch.quickshopper3k.ui.plan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import org.effekthasch.quickshopper3k.MainActivity
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.DialogAddItemBinding
import org.effekthasch.quickshopper3k.databinding.DialogImportItemsBinding
import org.effekthasch.quickshopper3k.databinding.FragmentPlanBinding
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.ui.FabController

class PlanFragment :
    org.effekthasch.quickshopper3k.ui.common.BaseListFragment<Item>(),
    FabController {

    private var _binding: FragmentPlanBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlanViewModel

    companion object {
        private const val PREFS_NAME = "plan_prefs"
        private const val KEY_DRAFT_ITEM = "draft_item_text"
    }

    override fun provideAdapter() = ItemsAdapter(
        onItemClick = {
            if (binding.addItemContainer.visibility == View.VISIBLE) {
                binding.addItemInput.clearFocus()
            }
        },
        onItemLongClick = { item ->
            if (binding.addItemContainer.visibility == View.VISIBLE) {
                binding.addItemInput.clearFocus()
            } else {
                showEditItemDialog(item)
            }
        },
        onStartDrag = { holder ->
            enableDragOn(holder)
        }
    )

    override fun onSwipeLeft(item: Item) {
        viewModel.handleSwipeLeft(item)
    }

    override fun onSwipeRight(item: Item) {
        viewModel.handleSwipeRight(item)
    }

    override fun persistOrder(items: List<Item>) {
        viewModel.updateItemOrderFromList(items) { result ->
            result.onFailure {
                Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBottomBarTextChanged(text: String) {
        saveDraft(text)
    }

    override fun bottomBarImeAction(): Int = EditorInfo.IME_ACTION_DONE

    override fun getSwipeRightTargetState(item: Item): String {
        return when (item.state) {
            org.effekthasch.quickshopper3k.domain.model.ItemState.ACTIVATED -> "Done"
            org.effekthasch.quickshopper3k.domain.model.ItemState.DEACTIVATED -> "Needed"
            org.effekthasch.quickshopper3k.domain.model.ItemState.POSTPONED -> "Needed"
        }
    }

    override fun getSwipeLeftTargetState(item: Item): String {
        return when (item.state) {
            org.effekthasch.quickshopper3k.domain.model.ItemState.ACTIVATED -> "N/A"
            org.effekthasch.quickshopper3k.domain.model.ItemState.DEACTIVATED -> "N/A"
            org.effekthasch.quickshopper3k.domain.model.ItemState.POSTPONED -> "Done"
        }
    }

    override fun onFabClick() {
        if (binding.addItemContainer.visibility == View.VISIBLE) {
            binding.addItemInput.clearFocus()
        } else {
            showInlineAddItem()
        }
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_import_items -> {
                showImportItemsDialog()
                true
            }

            R.id.action_activate_all -> {
                viewModel.activateAll { result ->
                    result.onSuccess {
                        Snackbar.make(binding.root, R.string.all_items_activated, Snackbar.LENGTH_SHORT).show()
                    }.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }

            R.id.action_deactivate_all -> {
                viewModel.deactivateAll { result ->
                    result.onSuccess {
                        Snackbar.make(binding.root, R.string.all_items_deactivated, Snackbar.LENGTH_SHORT).show()
                    }.onFailure {
                        Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                    }
                }
                true
            }

            R.id.action_settings -> {
                true
            }

            else -> false
        }
    }

    override fun updateFabIcon(iconRes: Int, contentDescription: String) {
    }

    override fun showFab() {
        (activity as? MainActivity)?.let {
        }
    }

    override fun hideFab() {
        (activity as? MainActivity)?.let {
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        viewModel = ViewModelProvider(
            this,
            PlanViewModelFactory(repository)
        )[PlanViewModel::class.java]

        setupRecyclerView(binding.itemsRecyclerView)
        setupBottomBar(binding.addItemContainer, binding.addItemInput)
        setupInlineAddItem()
        observeItems()
    }

    private fun setupInlineAddItem() {
        binding.addItemInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveInlineItem()
                true
            } else {
                false
            }
        }

        binding.addItemInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveDraft(binding.addItemInput.text?.toString() ?: "")
                hideInlineAddItem()
            }
        }

        binding.itemsRecyclerView.setOnTouchListener { _, _ ->
            if (binding.addItemContainer.visibility == View.VISIBLE) {
                binding.addItemInput.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun saveDraft(text: String) {
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_ITEM, text)
            .apply()
    }

    private fun loadDraft(): String {
        return requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_DRAFT_ITEM, "") ?: ""
    }

    private fun clearDraft() {
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT_ITEM)
            .apply()
    }

    private fun showInlineAddItem() {
        binding.addItemContainer.visibility = View.VISIBLE
        binding.addItemInput.setText(loadDraft())
        binding.addItemInput.requestFocus()
        binding.addItemInput.selectAll()
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.addItemInput, InputMethodManager.SHOW_IMPLICIT)
        (activity as? MainActivity)?.hideBottomBar()
    }

    private fun hideInlineAddItem() {
        binding.addItemContainer.visibility = View.GONE
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.addItemInput.windowToken, 0)
        (activity as? MainActivity)?.showBottomBar()
    }

    private fun saveInlineItem() {
        val name = binding.addItemInput.text?.toString() ?: ""
        binding.addItemInput.text.clear()

        if (name.isNotBlank()) {
            viewModel.addItem(name) { result ->
                result.onSuccess {
                    clearDraft()
                }.onFailure {
                    Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeItems() {
        viewModel.items.observe(viewLifecycleOwner) { items ->
            applyItems(items)
        }
    }

    private fun showEditItemDialog(item: Item) {
        val dialogBinding = DialogAddItemBinding.inflate(layoutInflater)
        dialogBinding.itemNameInput.setText(item.name)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_item_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = dialogBinding.itemNameInput.text?.toString() ?: ""
                if (newName.isNotBlank()) {
                    viewModel.updateItemName(item.id, newName) { result ->
                        result.onSuccess {
                            Snackbar.make(binding.root, R.string.item_updated, Snackbar.LENGTH_SHORT).show()
                        }.onFailure {
                            Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.error_empty_name, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                viewModel.deleteItem(item.id) { result ->
                    result.onSuccess {
                        Snackbar.make(binding.root, R.string.item_deleted, Snackbar.LENGTH_SHORT).show()
                    }.onFailure {
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

                    viewModel.importItems(text) { result ->
                        progressDialog.dismiss()
                        result.onSuccess { count ->
                            Snackbar.make(
                                binding.root,
                                getString(R.string.items_imported, count),
                                Snackbar.LENGTH_SHORT
                            ).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
