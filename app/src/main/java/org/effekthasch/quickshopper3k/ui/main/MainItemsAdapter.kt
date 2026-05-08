package org.effekthasch.quickshopper3k.ui.main

import android.view.LayoutInflater
import android.view.View
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.databinding.ItemShoppingItemBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

class MainItemsAdapter(
    private val onItemClick: () -> Boolean,
    private val onItemLongClick: (StoreItem, Float, Float) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : BaseDragAdapter<StoreItem, MainItemsAdapter.MainItemViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()

    override fun getStableId(item: StoreItem): Long = item.item.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainItemViewHolder {
        val binding = ItemShoppingItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MainItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MainItemViewHolder, position: Int) {
        holder.bind(backingItems[position], position)
    }

    override fun getItemCount(): Int = backingItems.size

    fun toggleSelection(position: Int) {
        if (position in selectedPositions) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
    }

    fun clearSelection() {
        val oldSelection = selectedPositions.toSet()
        selectedPositions.clear()
        oldSelection.forEach { notifyItemChanged(it) }
    }

    fun isSelected(position: Int): Boolean = position in selectedPositions

    fun getSelectedPositions(): Set<Int> = selectedPositions.toSet()

    fun getContiguousSelectionRange(draggedPosition: Int): IntRange? {
        if (draggedPosition !in selectedPositions) return null

        val sorted = selectedPositions.sorted()
        var start = draggedPosition
        var end = draggedPosition

        while (start - 1 in sorted && start > 0) start--
        while (end + 1 in sorted && end < backingItems.size - 1) end++

        return start..end
    }

    fun bunchSelection(anchorPosition: Int) {
        if (anchorPosition !in selectedPositions) return

        val sorted = selectedPositions.sorted()

        if (sorted.size <= 1) return

        val first = sorted.first()
        val last = sorted.last()
        if (last - first == sorted.size - 1) return

        val beforeAnchor = sorted.filter { it < anchorPosition }
        val afterAnchor = sorted.filter { it > anchorPosition }

        val itemsMap = sorted.associateWith { backingItems[it] }

        sorted.reversed().forEach { backingItems.removeAt(it) }

        val insertPos = sorted.first()

        val bunchedItems = beforeAnchor.map { itemsMap[it]!! } +
                listOf(itemsMap[anchorPosition]!!) +
                afterAnchor.map { itemsMap[it]!! }

        bunchedItems.forEachIndexed { index, item ->
            backingItems.add(insertPos + index, item)
        }

        selectedPositions.clear()
        for (i in 0 until bunchedItems.size) {
            selectedPositions.add(insertPos + i)
        }

        notifyDataSetChanged()
    }

    fun moveBlock(blockRange: IntRange, targetPosition: Int) {
        val blockSize = blockRange.count()
        val blockItems = blockRange.map { backingItems[it] }

        val actualTarget = if (targetPosition > blockRange.first) {
            targetPosition - blockSize + 1
        } else {
            targetPosition
        }

        if (actualTarget < blockRange.first) {
            for (i in 0 until blockSize) {
                val fromPos = blockRange.first + i
                backingItems.removeAt(fromPos)
                backingItems.add(actualTarget + i, blockItems[i])
                notifyItemMoved(fromPos, actualTarget + i)
            }
        } else {
            for (i in (blockSize - 1) downTo 0) {
                val fromPos = blockRange.first + i
                val toPos = actualTarget + i
                backingItems.removeAt(fromPos)
                backingItems.add(toPos, blockItems[i])
                notifyItemMoved(fromPos, toPos)
            }
        }

        val newPositions = mutableSetOf<Int>()
        for (i in 0 until blockSize) {
            newPositions.add(actualTarget + i)
        }
        selectedPositions.clear()
        selectedPositions.addAll(newPositions)
    }

    inner class MainItemViewHolder(
        private val binding: ItemShoppingItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastTouchRawX = 0f
        private var lastTouchRawY = 0f

        init {
            binding.root.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    lastTouchRawX = event.rawX
                    lastTouchRawY = event.rawY
                }
                false
            }
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val shouldSelect = onItemClick()
                    if (shouldSelect) {
                        toggleSelection(position)
                    }
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(backingItems[position], lastTouchRawX, lastTouchRawY)
                    true
                } else {
                    false
                }
            }
            binding.dragHandle.setOnTouchListener { _, _ ->
                onStartDrag(this)
                true
            }
        }

        fun bind(storeItem: StoreItem, position: Int) {
            binding.itemNameText.text = storeItem.item.name

            if (storeItem.item.amount > 1) {
                binding.itemAmountText.text = storeItem.item.amount.toString()
                binding.itemAmountText.visibility = View.VISIBLE
            } else {
                binding.itemAmountText.visibility = View.GONE
            }

            when (storeItem.item.state) {
                ItemState.ACTIVATED -> {
                    binding.itemStateIcon.visibility = View.GONE
                    binding.itemNameText.alpha = 1.0f
                }

                ItemState.DEACTIVATED -> {
                    binding.itemStateIcon.visibility = View.VISIBLE
                    binding.itemStateIcon.setImageResource(R.drawable.ic_state_done)
                    binding.itemNameText.alpha = 0.5f
                }

                ItemState.POSTPONED -> {
                    binding.itemStateIcon.visibility = View.VISIBLE
                    binding.itemStateIcon.setImageResource(R.drawable.ic_state_na)
                    binding.itemNameText.alpha = 0.7f
                }
            }

            if (!storeItem.isAvailable) {
                binding.itemNameText.setTypeface(binding.itemNameText.typeface, Typeface.ITALIC)
            } else {
                binding.itemNameText.setTypeface(null, Typeface.NORMAL)
            }

            if (isSelected(position)) {
                binding.root.setBackgroundColor(
                    binding.root.context.getColor(R.color.selection_highlight)
                )
            } else {
                val typedValue = android.util.TypedValue()
                binding.root.context.theme.resolveAttribute(
                    android.R.attr.colorBackground,
                    typedValue,
                    true
                )
                binding.root.setBackgroundColor(typedValue.data)
            }
        }
    }
}
