package org.effekthasch.quickshopper3k.ui.store

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.databinding.ItemStoreEditItemBinding
import org.effekthasch.quickshopper3k.domain.model.StoreItem

class StoreEditAdapter(
    private val onAvailabilityChanged: (StoreItem, Boolean) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<StoreItem, StoreEditAdapter.StoreEditViewHolder>(StoreItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreEditViewHolder {
        val binding = ItemStoreEditItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StoreEditViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreEditViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val list = currentList.toMutableList()
        val item = list.removeAt(fromPosition)
        list.add(toPosition, item)
        submitList(list)
    }

    inner class StoreEditViewHolder(
        private val binding: ItemStoreEditItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }

        fun bind(storeItem: StoreItem) {
            binding.itemNameText.text = storeItem.item.name
            binding.availabilityCheckbox.isChecked = storeItem.isAvailable

            binding.availabilityCheckbox.setOnCheckedChangeListener(null)

            binding.availabilityCheckbox.setOnCheckedChangeListener { _, isChecked ->
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onAvailabilityChanged(getItem(position), isChecked)
                }
            }
        }
    }

    class StoreItemDiffCallback : DiffUtil.ItemCallback<StoreItem>() {
        override fun areItemsTheSame(oldItem: StoreItem, newItem: StoreItem): Boolean {
            return oldItem.item.id == newItem.item.id
        }

        override fun areContentsTheSame(oldItem: StoreItem, newItem: StoreItem): Boolean {
            return oldItem == newItem
        }
    }
}
