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
    private lateinit var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper

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

        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dragCallback = MultiSelectDragCallback()
        itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        observeViewModel()
        super.onViewCreated(view, savedInstanceState)
    }

    override fun provideAdapter(): BaseDragAdapter<StoreItem, *> {
        storesAdapter = StoreItemsAdapter(
            onItemClick = { true },
            onItemLongClick = {},
            onStartDrag = { holder ->
                itemTouchHelper.startDrag(holder)
            }
        )
        return storesAdapter
    }

    fun clearSelection() {
        storesAdapter.clearSelection()
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

    inner class MultiSelectDragCallback : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        private var pendingSwipeAction: Pair<StoreItem, Int>? = null

        override fun getSwipeThreshold(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder): Float {
            return 0.3f
        }

        override fun getMovementFlags(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Int {
            val dragFlags =
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN
            val swipeFlags =
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            return makeMovementFlags(dragFlags, swipeFlags)
        }

        override fun onMove(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            target: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Boolean {
            val fromPosition = viewHolder.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition

            if (fromPosition == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                toPosition == androidx.recyclerview.widget.RecyclerView.NO_POSITION
            ) {
                return false
            }

            val blockRange = storesAdapter.getContiguousSelectionRange(fromPosition)

            if (blockRange != null) {
                if (toPosition !in blockRange) {
                    val movingUp = toPosition < fromPosition

                    val moveBlockTarget = if (movingUp) {
                        blockRange.first - 1
                    } else {
                        blockRange.last + 1
                    }

                    storesAdapter.moveBlock(blockRange, moveBlockTarget)
                }
            } else {
                storesAdapter.moveItem(fromPosition, toPosition)
            }

            return true
        }

        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                val item = adapter.getItems()[position]
                pendingSwipeAction = Pair(item, direction)
                storesAdapter.notifyItemChanged(position)
            }
        }

        override fun onSelectedChanged(
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
            actionState: Int
        ) {
            super.onSelectedChanged(viewHolder, actionState)
            when (actionState) {
                androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG -> {
                    isDragging = true
                    val position = viewHolder?.bindingAdapterPosition
                    if (position != null && position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        storesAdapter.bunchSelection(position)
                    }
                }

                androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE -> {
                    isSwiping = true
                }
            }
        }

        override fun clearView(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)

            if (isDragging) {
                val draggedPosition = viewHolder.bindingAdapterPosition
                if (draggedPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    val blockRange = storesAdapter.getContiguousSelectionRange(draggedPosition)
                    if (blockRange != null) {
                        for (i in blockRange) {
                            val vh = recyclerView.findViewHolderForAdapterPosition(i)
                            vh?.itemView?.translationY = 0f
                        }
                    }
                }

                val finalList = adapter.getItems()
                persistOrder(finalList)
                isDragging = false
            }

            if (isSwiping) {
                isSwiping = false
                pendingSwipeAction?.let { (item, direction) ->
                    when (direction) {
                        androidx.recyclerview.widget.ItemTouchHelper.LEFT -> onSwipeLeft(item)
                        androidx.recyclerview.widget.ItemTouchHelper.RIGHT -> onSwipeRight(item)
                    }
                    pendingSwipeAction = null
                    storesAdapter.setItems(storesAdapter.getItems())
                }
            }
        }

        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = true

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE) {
                drawSwipeBackground(c, viewHolder, dX)
            }

            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                val draggedPosition = viewHolder.bindingAdapterPosition
                if (draggedPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    val blockRange = storesAdapter.getContiguousSelectionRange(draggedPosition)
                    if (blockRange != null) {
                        for (i in blockRange) {
                            if (i != draggedPosition) {
                                val vh = recyclerView.findViewHolderForAdapterPosition(i)
                                vh?.itemView?.translationY = dY
                            }
                        }
                    }
                }
            }
        }

        private fun drawSwipeBackground(
            c: android.graphics.Canvas,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            dX: Float
        ) {
            val itemView = viewHolder.itemView
            val position = viewHolder.bindingAdapterPosition

            if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return

            val item = adapter.getItems()[position]
            val paint = android.graphics.Paint()
            val swipeThreshold = itemView.width * 0.3f

            if (dX > 0) {
                val targetState = getSwipeRightTargetState(item)
                val baseColor = getColorForState(targetState)
                val thresholdReached = dX >= swipeThreshold
                val color = if (thresholdReached) baseColor else desaturateColor(baseColor)

                paint.color = color
                c.drawRect(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left + dX,
                    itemView.bottom.toFloat(),
                    paint
                )

                drawStateIcon(c, itemView, dX, targetState, isLeftSide = true)
            } else if (dX < 0) {
                val targetState = getSwipeLeftTargetState(item)
                val baseColor = getColorForState(targetState)
                val thresholdReached = kotlin.math.abs(dX) >= swipeThreshold
                val color = if (thresholdReached) baseColor else desaturateColor(baseColor)

                paint.color = color
                c.drawRect(
                    itemView.right + dX,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                    paint
                )

                drawStateIcon(c, itemView, dX, targetState, isLeftSide = false)
            }
        }

        private fun drawStateIcon(
            c: android.graphics.Canvas,
            itemView: android.view.View,
            dX: Float,
            targetState: String,
            isLeftSide: Boolean
        ) {
            if (targetState.isEmpty()) return

            val iconPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                isAntiAlias = true
            }

            val iconSize = 50f
            val padding = 48f
            val iconX = if (isLeftSide) {
                itemView.left + padding
            } else {
                itemView.right - padding
            }
            val centerY = itemView.top + (itemView.height / 2f)

            when (targetState) {
                "Available" -> drawCheckmark(c, iconX, centerY, iconSize, iconPaint)
                "N/A" -> drawX(c, iconX, centerY, iconSize, iconPaint)
            }
        }

        private fun drawCheckmark(
            canvas: android.graphics.Canvas,
            centerX: Float,
            centerY: Float,
            size: Float,
            paint: android.graphics.Paint
        ) {
            val path = android.graphics.Path()
            path.moveTo(centerX - size * 0.3f, centerY)
            path.lineTo(centerX - size * 0.1f, centerY + size * 0.3f)
            path.lineTo(centerX + size * 0.4f, centerY - size * 0.3f)

            val strokePaint = android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.15f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            canvas.drawPath(path, strokePaint)
        }

        private fun drawX(
            canvas: android.graphics.Canvas,
            centerX: Float,
            centerY: Float,
            size: Float,
            paint: android.graphics.Paint
        ) {
            val strokePaint = android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.15f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            canvas.drawLine(
                centerX - size * 0.3f, centerY - size * 0.3f,
                centerX + size * 0.3f, centerY + size * 0.3f,
                strokePaint
            )
            canvas.drawLine(
                centerX + size * 0.3f, centerY - size * 0.3f,
                centerX - size * 0.3f, centerY + size * 0.3f,
                strokePaint
            )
        }

        private fun desaturateColor(color: Int): Int {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[1] = hsv[1] * 0.3f
            return android.graphics.Color.HSVToColor(hsv)
        }

        private fun getColorForState(state: String): Int {
            return when (state) {
                "Available" -> android.graphics.Color.parseColor("#4CAF50")
                "N/A" -> android.graphics.Color.parseColor("#F44336")
                else -> android.graphics.Color.GRAY
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
