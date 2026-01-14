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
            (activity as? org.effekthasch.quickshopper3k.MainActivity)?.updateStoreSelectorButton()
        }

        viewModel.allStores.observe(viewLifecycleOwner) { _ ->
            (activity as? org.effekthasch.quickshopper3k.MainActivity)?.updateStoreSelectorButton()
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
        } else {
            binding.emptyStateText.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
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

    override fun onResume() {
        super.onResume()
        (activity as? org.effekthasch.quickshopper3k.MainActivity)?.updateStoreSelectorButton()
    }

    fun getCurrentStoreName(): String {
        val currentStoreId = prefsManager.currentStoreId ?: return "◉"
        val stores = viewModel.allStores.value ?: return "◉"
        val store = stores.find { it.id == currentStoreId }
        return store?.name ?: "◉"
    }

    fun showStoreSelectionDialog() {
        val stores = viewModel.allStores.value ?: return
        if (stores.isEmpty()) return

        val storeNames = stores.map { store ->
            val prefix = if (store.specialtyStore) "★ " else ""
            prefix + store.name
        }.toTypedArray()

        val currentStoreId = prefsManager.currentStoreId
        val currentIndex = stores.indexOfFirst { it.id == currentStoreId }

        val anchorView = (activity as? org.effekthasch.quickshopper3k.MainActivity)
            ?.findViewById<android.view.View>(org.effekthasch.quickshopper3k.R.id.storeSelectorButton)
            ?: return

        val popupMenu = android.widget.PopupMenu(requireContext(), anchorView)
        stores.forEachIndexed { index, store ->
            val displayName = if (store.specialtyStore) "★ ${store.name}" else store.name
            popupMenu.menu.add(0, index, index, displayName)
        }

        popupMenu.setOnMenuItemClickListener { item ->
            val selectedStore = stores[item.itemId]
            viewModel.selectStore(selectedStore.id)
            true
        }

        popupMenu.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
