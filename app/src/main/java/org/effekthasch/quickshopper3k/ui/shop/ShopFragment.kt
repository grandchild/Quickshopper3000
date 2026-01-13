package org.effekthasch.quickshopper3k.ui.shop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.DialogAddItemBinding
import org.effekthasch.quickshopper3k.databinding.FragmentShopBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem

class ShopFragment :
    org.effekthasch.quickshopper3k.ui.common.BaseListFragment<org.effekthasch.quickshopper3k.domain.model.StoreItem>() {
    private var _binding: FragmentShopBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ShopViewModel


    private var currentStoreName: String? = null

    override fun provideAdapter() = StoreItemsAdapter(
        onItemClick = { /* keep existing click behavior if any */ },
        onItemLongClick = { /* keep existing long-click behavior if any */ },
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
            ItemState.POSTPONED -> ""
        }
    }

    override fun getSwipeLeftTargetState(item: StoreItem): String {
        return when (item.item.state) {
            ItemState.ACTIVATED -> "N/A"
            ItemState.DEACTIVATED -> ""
            ItemState.POSTPONED -> "Done"
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

        val database = AppDatabase.getDatabase(requireContext())
        val repository = ShoppingRepository(database)
        viewModel = ViewModelProvider(
            this,
            ShopViewModelFactory(repository)
        )[ShopViewModel::class.java]

        setupTopBar()
        setupRecyclerView(binding.itemsRecyclerView)
        setupBottomBar(binding.searchContainer, binding.searchInput)
        setBottomBarToggleViewCompat(binding.searchFab)
        setupFab()
        observeData()
    }

    private fun setupTopBar() {
        val topBarLayout =
            binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topBarLayout)
        val topBar = topBarLayout ?: return
        val modeTitle = topBar.findViewById<android.widget.TextView>(R.id.modeTitle)

        topBar.title = ""
        modeTitle.setText(R.string.shop_mode_title)

        val bgColor = ContextCompat.getColor(requireContext(), R.color.shop_mode_bg)
        topBar.setBackgroundColor(bgColor)

        requireActivity().window.statusBarColor = bgColor

        topBar.inflateMenu(R.menu.menu_shop)
        topBar.setOnMenuItemClickListener { menuItem ->
            onOptionsItemSelected(menuItem)
        }

        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                        if (diffX > 0) {
                            findNavController().navigate(R.id.action_shopFragment_to_planFragment)
                        }
                        return true
                    }
                }
                return false
            }
        })

        topBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event) ?: false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_switch_to_plan -> {
                findNavController().navigate(R.id.action_shopFragment_to_planFragment)
                true
            }

            R.id.action_select_store -> {
                Snackbar.make(binding.root, "Store selection page coming soon", Snackbar.LENGTH_SHORT)
                    .show()
                true
            }

            R.id.action_toggle_done_items -> {
                viewModel.toggleShowDoneItems()
                invalidateMenu()
                true
            }

            R.id.action_settings -> {
                true
            }

            R.id.action_edit_store -> {
                val currentStoreId = viewModel.currentStoreId.value
                if (currentStoreId != null) {
                    val bundle = Bundle().apply {
                        putLong("storeId", currentStoreId)
                    }
                    findNavController().navigate(R.id.action_shopFragment_to_storeEditFragment, bundle)
                } else {
                    Snackbar.make(binding.root, "Please select a store first", Snackbar.LENGTH_SHORT).show()
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun invalidateMenu() {
        val topBar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topBarLayout)
        topBar?.menu?.findItem(R.id.action_toggle_done_items)?.let { toggleItem ->
            val showDone = viewModel.showDoneItems.value ?: false
            toggleItem.title =
                if (showDone) "✓ ${getString(R.string.pref_show_done_items)}" else getString(R.string.pref_show_done_items)
        }
    }

    private fun setupFab() {
        binding.searchFab.setOnClickListener {
            showBottomBar(viewModel.searchQuery.value ?: "")
            binding.searchInput.selectAll()
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.clearFocus()
                hideBottomBar()
                updateSearchActiveBar()
                true
            } else {
                false
            }
        }

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideBottomBar()
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
    }


    private fun observeData() {
        viewModel.filteredItems.observe(viewLifecycleOwner) { items ->
            applyItems(items)
            updateSearchActiveBar()
        }

        viewModel.stores.observe(viewLifecycleOwner) { stores ->
        }

        viewModel.currentStoreId.observe(viewLifecycleOwner) { storeId ->
            viewModel.stores.value?.let { stores ->
                currentStoreName = stores.find { it.id == storeId }?.name
            }
        }

        viewModel.showDoneItems.observe(viewLifecycleOwner) {
            invalidateMenu()
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
        val imm = androidx.core.content.ContextCompat.getSystemService(
            requireContext(),
            android.view.inputmethod.InputMethodManager::class.java
        )
        imm?.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun hideInlineSearch() {
        binding.searchContainer.visibility = View.GONE
        val imm = androidx.core.content.ContextCompat.getSystemService(
            requireContext(),
            android.view.inputmethod.InputMethodManager::class.java
        )
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
