package org.effekthasch.quickshopper3k.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import org.effekthasch.quickshopper3k.MainActivity
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.FragmentShopBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.FabController
import org.effekthasch.quickshopper3k.util.PreferencesManager

class ShopFragment :
    org.effekthasch.quickshopper3k.ui.common.BaseListFragment<StoreItem>(),
    FabController {

    private var _binding: FragmentShopBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ShopViewModel
    private lateinit var prefsManager: PreferencesManager

    private var currentStoreName: String? = null

    override fun provideAdapter() = ShopItemsAdapter(
        onItemClick = {
            if (binding.searchContainer.visibility == View.VISIBLE) {
                binding.searchInput.clearFocus()
            }
        },
        onItemLongClick = { },
        onStartDrag = { holder -> enableDragOn(holder) }
    )

    override fun onSwipeLeft(item: StoreItem) {
        viewModel.handleSwipeLeft(item.item)
    }

    override fun onSwipeRight(item: StoreItem) {
        viewModel.handleSwipeRight(item.item)
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
    }

    override fun bottomBarImeAction(): Int = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH

    override fun getSwipeRightTargetState(item: StoreItem): String {
        return when (item.item.state) {
            ItemState.ACTIVATED -> "Done"
            ItemState.DEACTIVATED -> "N/A"
            ItemState.POSTPONED -> "Needed"
        }
    }

    override fun getSwipeLeftTargetState(item: StoreItem): String {
        return when (item.item.state) {
            ItemState.ACTIVATED -> "N/A"
            ItemState.DEACTIVATED -> ""
            ItemState.POSTPONED -> "Done"
        }
    }

    override fun onFabClick() {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            binding.searchInput.clearFocus()
        } else {
            showInlineSearch()
        }
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_select_store -> {
                Snackbar.make(binding.root, "Store selection coming soon", Snackbar.LENGTH_SHORT).show()
                true
            }

            R.id.action_toggle_done_items -> {
                viewModel.toggleShowDoneItems()
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
        _binding = FragmentShopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        viewModel = ViewModelProvider(
            this,
            ShopViewModelFactory(repository)
        )[ShopViewModel::class.java]

        val initialStoreId = prefsManager.selectedShopStoreIds.firstOrNull() ?: 0L
        viewModel.setCurrentStore(initialStoreId)

        setupRecyclerView(binding.itemsRecyclerView)
        setupBottomBar(binding.searchContainer, binding.searchInput)
        setupInlineSearch()
        observeData()
    }

    private fun setupInlineSearch() {
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.clearFocus()
                hideInlineSearch()
                updateSearchActiveBar()
                true
            } else {
                false
            }
        }

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideInlineSearch()
                updateSearchActiveBar()
            }
        }

        binding.itemsRecyclerView.setOnTouchListener { _, _ ->
            if (binding.searchContainer.visibility == View.VISIBLE) {
                binding.searchInput.clearFocus()
                true
            } else {
                false
            }
        }

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun observeData() {
        viewModel.filteredItems.observe(viewLifecycleOwner) { items ->
            applyItems(items)
            updateSearchActiveBar()
        }

        viewModel.stores.observe(viewLifecycleOwner) { stores ->
            updateStoreSpinner(stores)
        }

        viewModel.currentStoreId.observe(viewLifecycleOwner) { storeId ->
            viewModel.stores.value?.let { stores ->
                currentStoreName = stores.find { it.id == storeId }?.name
            }
        }

        viewModel.showDoneItems.observe(viewLifecycleOwner) {
        }
    }

    private fun updateStoreSpinner(stores: List<org.effekthasch.quickshopper3k.domain.model.Store>) {
        binding.storeHeader.visibility = View.VISIBLE

        val allStores = listOf(
            org.effekthasch.quickshopper3k.domain.model.Store(0L, "All items", false)
        ) + stores

        val storeNames = allStores.map { store ->
            if (store.id == 0L) {
                store.name
            } else {
                val prefix = if (store.specialtyStore) "★ " else ""
                prefix + store.name
            }
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            storeNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.storeSelector.adapter = adapter

        val currentStoreId = viewModel.currentStoreId.value
        val currentIndex = allStores.indexOfFirst { it.id == currentStoreId }
        if (currentIndex >= 0) {
            binding.storeSelector.setSelection(currentIndex)
        }

        binding.storeSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStore = allStores[position]
                if (selectedStore.id != viewModel.currentStoreId.value) {
                    viewModel.setCurrentStore(selectedStore.id)
                    prefsManager.selectedShopStoreIds = setOf(selectedStore.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun updateSearchActiveBar() {
        val q = viewModel.searchQuery.value ?: ""
        val barVisible = q.isNotBlank() && binding.searchContainer.visibility != View.VISIBLE
        binding.searchActiveBar.visibility = if (barVisible) View.VISIBLE else View.GONE
        binding.searchActiveText.text = q
        binding.searchActiveClear.setOnClickListener {
            viewModel.setSearchQuery("")
        }
    }

    private fun showInlineSearch() {
        binding.searchContainer.visibility = View.VISIBLE
        binding.searchInput.setText(viewModel.searchQuery.value ?: "")
        binding.searchInput.requestFocus()
        binding.searchInput.selectAll()
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        (activity as? MainActivity)?.hideBottomBar()
    }

    private fun hideInlineSearch() {
        binding.searchContainer.visibility = View.GONE
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        (activity as? MainActivity)?.showBottomBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
