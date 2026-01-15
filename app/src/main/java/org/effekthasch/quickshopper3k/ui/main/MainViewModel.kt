package org.effekthasch.quickshopper3k.ui.main

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
import org.effekthasch.quickshopper3k.util.DoneVisibilityMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(private val repository: ShoppingRepository) : ViewModel() {
    private val _currentStoreId = MutableStateFlow<Long?>(null)
    private val _searchQuery = MutableLiveData("")
    private val _doneVisibilityMode = MutableLiveData(DoneVisibilityMode.NONE)
    private val _recentDoneMinutes = MutableLiveData(60)
    private val _flushRecentlyDoneAt = MutableLiveData<Long?>(null)
    private val _isDragging = MutableLiveData(false)

    val stores: LiveData<List<Store>> = repository.getAllStores().asLiveData()

    val items: LiveData<List<StoreItem>> = _currentStoreId.flatMapLatest { storeId ->
        repository.getItemsForStore(storeId ?: 0L)
    }.asLiveData()

    val filteredItems: LiveData<List<StoreItem>> = MediatorLiveData<List<StoreItem>>().apply {
        fun update() {
            val allItems = items.value ?: emptyList()
            val query = _searchQuery.value ?: ""
            val visibilityMode = _doneVisibilityMode.value ?: DoneVisibilityMode.NONE
            val recentMinutes = _recentDoneMinutes.value ?: 60
            val flushAt = _flushRecentlyDoneAt.value
            val isDragging = _isDragging.value ?: false
            val currentStore = _currentStoreId.value
            val now = System.currentTimeMillis()
            val minutesThreshold = now - (recentMinutes * 60 * 1000L)
            val recentThreshold = if (flushAt != null && flushAt > minutesThreshold) flushAt else minutesThreshold

            value = allItems.filter { storeItem ->
                val matchesSearch = if (query.isBlank()) {
                    true
                } else {
                    storeItem.item.name.contains(query, ignoreCase = true)
                }

                val matchesVisibility = when {
                    isDragging -> true
                    storeItem.item.state != ItemState.DEACTIVATED -> true
                    visibilityMode == DoneVisibilityMode.ALL -> true
                    visibilityMode == DoneVisibilityMode.RECENT -> {
                        val changedAt = storeItem.item.stateChangedAt
                        changedAt != null && changedAt >= recentThreshold
                    }

                    else -> false
                }

                val matchesAvailability = if (currentStore == null || currentStore == 0L) {
                    true
                } else {
                    storeItem.isAvailable
                }

                matchesSearch && matchesVisibility && matchesAvailability
            }
        }

        addSource(items) { update() }
        addSource(_searchQuery) { update() }
        addSource(_doneVisibilityMode) { update() }
        addSource(_recentDoneMinutes) { update() }
        addSource(_flushRecentlyDoneAt) { update() }
        addSource(_isDragging) { update() }
        addSource(_currentStoreId.asLiveData()) { update() }
    }

    val currentStoreId: LiveData<Long?> = _currentStoreId.asLiveData()
    val searchQuery: LiveData<String> = _searchQuery
    val doneVisibilityMode: LiveData<DoneVisibilityMode> = _doneVisibilityMode

    fun hasExactMatch(query: String): Boolean {
        val allItems = items.value ?: return false
        return allItems.any { it.item.name.equals(query, ignoreCase = true) }
    }

    fun setCurrentStore(storeId: Long?) {
        _currentStoreId.value = storeId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun cycleDoneVisibilityMode() {
        val current = _doneVisibilityMode.value ?: DoneVisibilityMode.NONE
        _doneVisibilityMode.value = current.next()
    }

    fun setDoneVisibilityMode(mode: DoneVisibilityMode) {
        _doneVisibilityMode.value = mode
    }

    fun setRecentDoneMinutes(minutes: Int) {
        _recentDoneMinutes.value = minutes
    }

    fun setFlushRecentlyDoneAt(timestamp: Long?) {
        _flushRecentlyDoneAt.value = timestamp
    }

    fun flushRecentlyDone() {
        _flushRecentlyDoneAt.value = System.currentTimeMillis()
    }

    fun setDragging(isDragging: Boolean) {
        _isDragging.value = isDragging
    }

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
