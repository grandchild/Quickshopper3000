package org.effekthasch.quickshopper3k.ui.stores

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.FragmentStoresBinding
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter
import org.effekthasch.quickshopper3k.ui.common.BaseListFragment
import org.effekthasch.quickshopper3k.ui.shop.StoreItemsAdapter
import org.effekthasch.quickshopper3k.util.PreferencesManager

class StoresFragment : BaseListFragment<StoreItem>(), org.effekthasch.quickshopper3k.ui.FabController {
    private var _binding: FragmentStoresBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: StoresViewModel
    private lateinit var prefsManager: PreferencesManager
    private lateinit var storesAdapter: StoreItemsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoresBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefsManager = PreferencesManager(requireContext())

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        val factory = StoresViewModelFactory(repository, prefsManager)
        viewModel = ViewModelProvider(this, factory)[StoresViewModel::class.java]

        recyclerView = binding.recyclerView
        bottomBarContainer = binding.root as ViewGroup
        bottomBarInput = EditText(requireContext())

        adapter = provideAdapter()
        setupRecyclerView(recyclerView)

        observeViewModel()
        super.onViewCreated(view, savedInstanceState)
    }

    override fun provideAdapter(): BaseDragAdapter<StoreItem, *> {
        storesAdapter = StoreItemsAdapter(
            onItemClick = {},
            onItemLongClick = {},
            onStartDrag = { holder ->
                enableDragOn(holder)
            }
        )
        return storesAdapter
    }

    private fun observeViewModel() {
        viewModel.storeItems.observe(viewLifecycleOwner) { items ->
            applyItems(items)
            updateEmptyState(items.isEmpty())
        }

        viewModel.currentStore.observe(viewLifecycleOwner) { _ ->
        }

        viewModel.allStores.observe(viewLifecycleOwner) { stores ->
            updateStoreSpinner(stores)
        }

        viewModel.error.observe(viewLifecycleOwner) { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (prefsManager.currentStoreId == null) {
            binding.emptyStateText.visibility = View.VISIBLE
            binding.emptyStateText.text = getString(R.string.no_stores)
            binding.recyclerView.visibility = View.GONE
            binding.storeHeader.visibility = View.GONE
        } else {
            binding.emptyStateText.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.storeHeader.visibility = View.VISIBLE
        }
    }

    private fun updateStoreSpinner(stores: List<org.effekthasch.quickshopper3k.domain.model.Store>) {
        if (stores.isEmpty()) {
            binding.storeHeader.visibility = View.GONE
            return
        }

        binding.storeHeader.visibility = View.VISIBLE

        val storeNames = stores.map { store ->
            val prefix = if (store.specialtyStore) "★ " else ""
            prefix + store.name
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            storeNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.storeSelector.adapter = adapter

        val currentStoreId = prefsManager.currentStoreId
        val currentIndex = stores.indexOfFirst { it.id == currentStoreId }
        if (currentIndex >= 0) {
            binding.storeSelector.setSelection(currentIndex)
        }

        binding.storeSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStore = stores[position]
                if (selectedStore.id != prefsManager.currentStoreId) {
                    viewModel.selectStore(selectedStore.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }


    override fun onFabClick() {
        showAddStoreDialog()
    }

    private fun showAddStoreDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_store, null)
        val input = dialogView.findViewById<EditText>(R.id.store_name_input)
        val specialtyCheckbox = dialogView.findViewById<CheckBox>(R.id.specialty_store_checkbox)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_store)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.addStore(name, specialtyCheckbox.isChecked)
                } else {
                    Snackbar.make(binding.root, R.string.store_name_required, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditStoreNameDialog(currentName: String) {
        val input = EditText(requireContext())
        input.setText(currentName)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_store)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.updateStoreName(name)
                } else {
                    Snackbar.make(binding.root, R.string.store_name_required, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSwipeLeft(item: StoreItem) {
        toggleItemAvailability(item)
    }

    override fun onSwipeRight(item: StoreItem) {
        toggleItemAvailability(item)
    }

    private fun toggleItemAvailability(item: StoreItem) {
        val items = (adapter as BaseDragAdapter<StoreItem, *>).getItems().toMutableList()
        val idx = items.indexOfFirst { it.item.id == item.item.id }
        if (idx >= 0) {
            val currentItem = items[idx]
            val newAvailability = !currentItem.isAvailable
            items[idx] = currentItem.copy(isAvailable = newAvailability)
            adapter.setItems(items)
            viewModel.setItemAvailability(item.item.id, newAvailability)
        }
    }

    override fun persistOrder(items: List<StoreItem>) {
        val storeId = prefsManager.currentStoreId ?: return
        val orders = items.mapIndexed { index, storeItem ->
            storeItem.item.id to index
        }
        viewModel.updateItemOrders(storeId, orders)
    }

    override fun onBottomBarTextChanged(text: String) {
    }

    override fun bottomBarImeAction(): Int {
        return android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }

    override fun getSwipeRightTargetState(item: StoreItem): String {
        return if (item.isAvailable) "N/A" else "Available"
    }

    override fun getSwipeLeftTargetState(item: StoreItem): String {
        return if (item.isAvailable) "N/A" else "Available"
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_edit_store -> {
                viewModel.currentStore.value?.let { store ->
                    showEditStoreNameDialog(store.name)
                }
                true
            }

            R.id.action_delete_store -> {
                viewModel.currentStore.value?.let { store ->
                    showDeleteStoreDialog(store.id, store.name)
                }
                true
            }

            else -> false
        }
    }

    private fun showDeleteStoreDialog(storeId: Long, storeName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_store)
            .setMessage(getString(R.string.delete_store_confirmation, storeName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteStore(storeId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun updateFabIcon(iconRes: Int, contentDescription: String) {
    }

    override fun showFab() {
    }

    override fun hideFab() {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
