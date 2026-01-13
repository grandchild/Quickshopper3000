package org.effekthasch.quickshopper3k.ui.common

import androidx.recyclerview.widget.RecyclerView

abstract class BaseDragAdapter<T, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    protected val backingItems: MutableList<T> = mutableListOf()

    init {
        setHasStableIds(true)
    }

    fun setItems(newItems: List<T>) {
        backingItems.clear()
        backingItems.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<T> = backingItems

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        if (fromPosition !in backingItems.indices || toPosition !in backingItems.indices) return
        val item = backingItems.removeAt(fromPosition)
        backingItems.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    final override fun getItemId(position: Int): Long = getStableId(backingItems[position])

    protected abstract fun getStableId(item: T): Long
}
