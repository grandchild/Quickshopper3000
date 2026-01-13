package org.effekthasch.quickshopper3k.ui.shop

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem

class ShopViewModel(private val repository: ShoppingRepository) : ViewModel() {
    private val _currentStoreId = MutableStateFlow<Long?>(null)
    private val _searchQuery = MutableLiveData("")
    private val _showDoneItems = MutableLiveData(false)
    private val _isDragging = MutableLiveData(false)

    val stores: LiveData<List<Store>> = repository.getAllStores().asLiveData()

    val items: LiveData<List<StoreItem>> = _currentStoreId.flatMapLatest { storeId ->
        repository.getItemsForStore(storeId ?: 0L)
    }.asLiveData()

    val filteredItems: LiveData<List<StoreItem>> = MediatorLiveData<List<StoreItem>>().apply {
        fun update() {
            val allItems = items.value ?: emptyList()
            val query = _searchQuery.value ?: ""
            val showDone = _showDoneItems.value ?: false
            val isDragging = _isDragging.value ?: false

            value = allItems.filter { storeItem ->
                val matchesSearch = if (query.isBlank()) {
                    true
                } else {
                    storeItem.item.name.contains(query, ignoreCase = true)
                }

                val matchesVisibility = if (showDone || isDragging) {
                    true
                } else {
                    storeItem.item.state != ItemState.DEACTIVATED
                }

                matchesSearch && matchesVisibility
            }
        }

        addSource(items) { update() }
        addSource(_searchQuery) { update() }
        addSource(_showDoneItems) { update() }
        addSource(_isDragging) { update() }
    }

    val currentStoreId: LiveData<Long?> = _currentStoreId.asLiveData()
    val searchQuery: LiveData<String> = _searchQuery
    val showDoneItems: LiveData<Boolean> = _showDoneItems

    fun setCurrentStore(storeId: Long?) {
        _currentStoreId.value = storeId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleShowDoneItems() {
        _showDoneItems.value = !(_showDoneItems.value ?: false)
    }

    fun setDragging(isDragging: Boolean) {
        _isDragging.value = isDragging
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
            ItemState.DEACTIVATED -> activateItem(item.id)
            ItemState.POSTPONED -> deactivateItem(item.id)
        }
    }

    fun handleSwipeRight(item: Item) {
        when (item.state) {
            ItemState.ACTIVATED -> deactivateItem(item.id)
            ItemState.DEACTIVATED -> postponeItem(item.id)
            ItemState.POSTPONED -> activateItem(item.id)
        }
    }

    fun updateItemOrderFromList(finalList: List<StoreItem>, onResult: (Result<Unit>) -> Unit) {
        val storeId = _currentStoreId.value ?: 0L

        viewModelScope.launch {
            val orders = finalList.mapIndexed { index, storeItem ->
                storeItem.item.id to index
            }
            val result = repository.updateStoreItemOrders(storeId, orders)
            onResult(result)
        }
    }
}
