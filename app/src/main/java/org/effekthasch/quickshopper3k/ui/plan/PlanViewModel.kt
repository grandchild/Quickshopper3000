package org.effekthasch.quickshopper3k.ui.plan

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState

class PlanViewModel(private val repository: ShoppingRepository) : ViewModel() {
    private val _pendingOrderUpdates = mutableMapOf<Long, Int>()

    val items: LiveData<List<Item>> = repository.getAllItems().asLiveData()

    fun addItem(name: String, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            val result = repository.addItem(name.trim())
            onResult(result)
        }
    }

    fun updateItemName(itemId: Long, newName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.updateItemName(itemId, newName.trim())
            onResult(result)
        }
    }

    fun deleteItem(itemId: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.deleteItem(itemId)
            onResult(result)
        }
    }

    fun activateItem(itemId: Long) {
        viewModelScope.launch {
            repository.setItemState(itemId, ItemState.ACTIVATED)
        }
    }

    fun deactivateItem(itemId: Long) {
        viewModelScope.launch {
            repository.setItemState(itemId, ItemState.DEACTIVATED)
        }
    }

    fun postponeItem(itemId: Long) {
        viewModelScope.launch {
            repository.setItemState(itemId, ItemState.POSTPONED)
        }
    }

    fun handleSwipeLeft(item: Item) {
        when (item.state) {
            ItemState.ACTIVATED -> postponeItem(item.id)
            ItemState.DEACTIVATED -> postponeItem(item.id)
            ItemState.POSTPONED -> deactivateItem(item.id)
        }
    }

    fun handleSwipeRight(item: Item) {
        when (item.state) {
            ItemState.ACTIVATED -> deactivateItem(item.id)
            ItemState.DEACTIVATED -> activateItem(item.id)
            ItemState.POSTPONED -> activateItem(item.id)
        }
    }

    fun updateItemOrderFromList(finalList: List<Item>, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val orders = finalList.mapIndexed { index, item ->
                item.id to index
            }
            val result = repository.updateStoreItemOrders(0L, orders)
            onResult(result)
        }
    }

    fun importItems(text: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val lines = text.lines()
                .map { it.trim() }
                .map { line ->
                    var stripped = line
                    while (stripped.startsWith("-") || stripped.startsWith("*")) {
                        stripped = stripped.drop(1).trimStart()
                    }
                    stripped
                }
                .filter { it.isNotBlank() }

            if (lines.isEmpty()) {
                onResult(Result.failure(Exception("No items to import")))
                return@launch
            }

            val result = repository.bulkAddItems(lines)
            onResult(result)
        }
    }

    fun activateAll(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.setAllItemsToState(ItemState.ACTIVATED)
            onResult(result)
        }
    }

    fun deactivateAll(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.setAllItemsToState(ItemState.DEACTIVATED)
            onResult(result)
        }
    }
}
