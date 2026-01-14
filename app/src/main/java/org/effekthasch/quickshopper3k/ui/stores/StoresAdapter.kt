package org.effekthasch.quickshopper3k.ui.stores

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

class StoreItemsAdapter(
    private val onItemClick: (StoreItem) -> Unit
) : BaseDragAdapter<StoreItem, StoreItemsAdapter.StoreItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_edit, parent, false)
        return StoreItemViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: StoreItemViewHolder, position: Int) {
        val item = backingItems[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = backingItems.size

    override fun getStableId(item: StoreItem): Long = item.item.id

    class StoreItemViewHolder(
        itemView: View,
        private val onItemClick: (StoreItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.item_name)
        private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle)

        fun bind(storeItem: StoreItem) {
            nameText.text = storeItem.item.name

            if (storeItem.isAvailable) {
                nameText.alpha = 1.0f
                nameText.paintFlags = nameText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                nameText.alpha = 0.4f
                nameText.paintFlags = nameText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            itemView.setOnClickListener {
                onItemClick(storeItem)
            }
        }
    }
}
