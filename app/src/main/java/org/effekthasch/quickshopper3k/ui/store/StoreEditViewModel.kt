package org.effekthasch.quickshopper3k.ui.store

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem

class StoreEditViewModel(private val repository: ShoppingRepository) : ViewModel() {
    private val _storeId = MutableStateFlow<Long?>(null)
    private val _pendingOrderUpdates = mutableMapOf<Long, Int>()
    private val _store = MutableLiveData<Store?>()

    val store: LiveData<Store?> = _store

    val items: LiveData<List<StoreItem>> = _storeId.flatMapLatest { storeId ->
        if (storeId != null) {
            repository.getItemsForStore(storeId)
        } else {
            repository.getItemsForStore(0L)
        }
    }.asLiveData()

    fun setStoreId(storeId: Long) {
        _storeId.value = storeId
        viewModelScope.launch {
            _store.value = repository.getStoreById(storeId)
        }
    }

    fun setItemAvailability(itemId: Long, isAvailable: Boolean, onResult: (Result<Unit>) -> Unit) {
        val storeId = _storeId.value ?: return
        viewModelScope.launch {
            val result = repository.setItemAvailability(storeId, itemId, isAvailable)
            onResult(result)
        }
    }

    fun updateItemOrder(fromPosition: Int, toPosition: Int, currentList: List<StoreItem>) {
        val storeId = _storeId.value ?: 0L

        val mutableList = currentList.toMutableList()
        val item = mutableList.removeAt(fromPosition)
        mutableList.add(toPosition, item)

        mutableList.forEachIndexed { index, storeItem ->
            _pendingOrderUpdates[storeItem.item.id] = index
        }
    }

    fun savePendingOrderUpdates(onResult: (Result<Unit>) -> Unit) {
        val storeId = _storeId.value ?: 0L
        if (_pendingOrderUpdates.isEmpty()) {
            onResult(Result.success(Unit))
            return
        }

        viewModelScope.launch {
            val orders = _pendingOrderUpdates.map { (itemId, sortOrder) ->
                itemId to sortOrder
            }
            val result = repository.updateStoreItemOrders(storeId, orders)
            if (result.isSuccess) {
                _pendingOrderUpdates.clear()
            }
            onResult(result)
        }
    }

    fun deleteStore(onResult: (Result<Unit>) -> Unit) {
        val storeId = _storeId.value ?: return
        if (storeId == 0L) {
            onResult(Result.failure(IllegalArgumentException("Cannot delete default store")))
            return
        }
        viewModelScope.launch {
            val result = repository.deleteStore(storeId)
            onResult(result)
        }
    }

    fun updateStoreName(newName: String, onResult: (Result<Unit>) -> Unit) {
        val storeId = _storeId.value ?: return
        viewModelScope.launch {
            val result = repository.updateStoreName(storeId, newName)
            onResult(result)
        }
    }
}
