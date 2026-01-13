package org.effekthasch.quickshopper3k.ui.plan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.databinding.ItemShoppingItemBinding
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

class ItemsAdapter(
    private val onItemClick: (Item) -> Unit,
    private val onItemLongClick: (Item) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : BaseDragAdapter<Item, ItemsAdapter.ItemViewHolder>() {


    override fun getStableId(item: Item): Long = item.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemShoppingItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(backingItems[position])
    }

    override fun getItemCount(): Int = backingItems.size

    inner class ItemViewHolder(
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

        fun bind(item: Item) {
            binding.itemNameText.text = item.name
            binding.itemStateText.text = when (item.state) {
                ItemState.DEACTIVATED -> "Done"
                ItemState.POSTPONED -> "N/A"
                else -> ""
            }
            binding.itemStateText.visibility = if (item.state == ItemState.ACTIVATED) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }
}
