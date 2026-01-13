package org.effekthasch.quickshopper3k.ui.shop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.databinding.ItemShoppingItemBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

class StoreItemsAdapter(
    private val onItemClick: (StoreItem) -> Unit,
    private val onItemLongClick: (StoreItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : BaseDragAdapter<StoreItem, StoreItemsAdapter.StoreItemViewHolder>() {


    override fun getStableId(item: StoreItem): Long = item.item.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreItemViewHolder {
        val binding = ItemShoppingItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StoreItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreItemViewHolder, position: Int) {
        holder.bind(backingItems[position])
    }

    override fun getItemCount(): Int = backingItems.size

    inner class StoreItemViewHolder(
        private val binding: ItemShoppingItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(backingItems[position])
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(backingItems[position])
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

        fun bind(storeItem: StoreItem) {
            binding.itemNameText.text = storeItem.item.name
            binding.itemStateText.text = when (storeItem.item.state) {
                ItemState.DEACTIVATED -> "Done"
                ItemState.POSTPONED -> "N/A"
                else -> ""
            }
            binding.itemStateText.visibility = if (storeItem.item.state == ItemState.ACTIVATED) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }
}
