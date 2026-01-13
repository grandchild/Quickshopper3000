package org.effekthasch.quickshopper3k.ui.plan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.databinding.DialogAddItemBinding
import org.effekthasch.quickshopper3k.databinding.FragmentPlanBinding
import org.effekthasch.quickshopper3k.domain.model.Item

class PlanFragment :
    org.effekthasch.quickshopper3k.ui.common.BaseListFragment<org.effekthasch.quickshopper3k.domain.model.Item>() {
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

        setupTopBar()
        setupRecyclerView(binding.itemsRecyclerView)
        setupBottomBar(binding.addItemContainer, binding.addItemInput)
        setBottomBarToggleViewCompat(binding.addItemFab)
        setupFab()
        observeItems()
    }

    private fun setupTopBar() {
        val topBarLayout =
            binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topBarLayout)
        val topBar = topBarLayout ?: return
        val modeTitle = topBar.findViewById<android.widget.TextView>(R.id.modeTitle)

        topBar.title = ""
        modeTitle.setText(R.string.plan_mode_title)

        val bgColor = ContextCompat.getColor(requireContext(), R.color.plan_mode_bg)
        topBar.setBackgroundColor(bgColor)

        requireActivity().window.statusBarColor = bgColor

        topBar.inflateMenu(R.menu.menu_plan)
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
                        if (diffX < 0) {
                            findNavController().navigate(R.id.action_planFragment_to_shopFragment)
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
            R.id.action_switch_to_shop -> {
                findNavController().navigate(R.id.action_planFragment_to_shopFragment)
                true
            }

            R.id.action_settings -> {
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }


    private fun setupFab() {
        binding.addItemFab.setOnClickListener {
            showBottomBar(loadDraft())
        }

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
        binding.addItemFab.hide()
        binding.addItemInput.setText(loadDraft())
        binding.addItemInput.requestFocus()
        binding.addItemInput.selectAll()
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.addItemInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideInlineAddItem() {
        binding.addItemContainer.visibility = View.GONE
        binding.addItemFab.show()
        val imm = getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.addItemInput.windowToken, 0)
    }

    private fun saveInlineItem() {
        val name = binding.addItemInput.text?.toString() ?: ""
        binding.addItemInput.text.clear()
        binding.addItemInput.clearFocus()

        if (name.isNotBlank()) {
            viewModel.addItem(name) { result ->
                result.onSuccess {
                    clearDraft()
                    Snackbar.make(binding.root, R.string.item_added, Snackbar.LENGTH_SHORT).show()
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

    private fun showAddItemDialog() {
        val dialogBinding = DialogAddItemBinding.inflate(layoutInflater)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_item_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = dialogBinding.itemNameInput.text?.toString() ?: ""
                if (name.isNotBlank()) {
                    viewModel.addItem(name) { result ->
                        result.onSuccess {
                            Snackbar.make(binding.root, R.string.item_added, Snackbar.LENGTH_SHORT).show()
                        }.onFailure {
                            Snackbar.make(binding.root, R.string.error_occurred, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.error_empty_name, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialogBinding.itemNameInput.requestFocus()
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


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
