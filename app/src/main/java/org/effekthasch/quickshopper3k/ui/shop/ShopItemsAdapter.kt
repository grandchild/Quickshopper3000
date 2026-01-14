package org.effekthasch.quickshopper3k.ui.shop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.databinding.ItemShoppingItemBinding
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

class ShopItemsAdapter(
    private val onItemClick: (StoreItem) -> Unit,
    private val onItemLongClick: (StoreItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : BaseDragAdapter<StoreItem, ShopItemsAdapter.ShopItemViewHolder>() {

    override fun getStableId(item: StoreItem): Long = item.item.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShopItemViewHolder {
        val binding = ItemShoppingItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ShopItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShopItemViewHolder, position: Int) {
        holder.bind(backingItems[position])
    }

    override fun getItemCount(): Int = backingItems.size

    inner class ShopItemViewHolder(
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

            when (storeItem.item.state) {
                ItemState.DEACTIVATED -> {
                    binding.itemStateIcon.setImageResource(R.drawable.ic_state_done)
                    binding.itemStateIcon.visibility = View.VISIBLE
                    binding.itemNameText.alpha = 0.5f
                }

                ItemState.POSTPONED -> {
                    binding.itemStateIcon.setImageResource(R.drawable.ic_state_na)
                    binding.itemStateIcon.visibility = View.VISIBLE
                    binding.itemNameText.alpha = 1.0f
                }

                ItemState.ACTIVATED -> {
                    binding.itemStateIcon.visibility = View.INVISIBLE
                    binding.itemNameText.alpha = 1.0f
                }
            }
        }
    }
}
