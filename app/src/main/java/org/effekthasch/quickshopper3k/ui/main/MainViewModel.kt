package org.effekthasch.quickshopper3k.ui.main

import androidx.lifecycle.LiveData
import java.text.Normalizer
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
    companion object {
        private val COMBINING_MARKS_REGEX = Regex("\\p{M}")
    }

    data class ImportPreviewItem(
        val name: String,
        val isExisting: Boolean
    )

    data class ImportPreview(
        val orderedItems: List<ImportPreviewItem>,
        val existingItems: List<String>,
        val newItems: List<String>
    )

    private val _currentStoreId = MutableStateFlow<Long?>(null)
    private val _searchQuery = MutableLiveData("")
    private val _doneVisibilityMode = MutableLiveData(DoneVisibilityMode.RECENT)
    private val _recentDoneMinutes = MutableLiveData(60)
    private val _historyDays = MutableLiveData(183)
    private val _mistakeDoneSeconds = MutableLiveData(20)
    private val _reactivateMinSeconds = MutableLiveData(3600)
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
            val visibilityMode = _doneVisibilityMode.value ?: DoneVisibilityMode.RECENT
            val recentMinutes = _recentDoneMinutes.value ?: 60
            val flushAt = _flushRecentlyDoneAt.value
            val isDragging = _isDragging.value ?: false
            val currentStore = _currentStoreId.value
            val now = System.currentTimeMillis()
            val minutesThreshold = now - (recentMinutes * 60 * 1000L)
            val recentThreshold = if (flushAt != null && flushAt > minutesThreshold) flushAt else minutesThreshold

            val normalizedQuery = if (query.isBlank()) "" else normalizeForSearch(query)

            fun matchesSearch(storeItem: StoreItem): Boolean = query.isBlank() ||
                    normalizeForSearch(storeItem.item.name).contains(normalizedQuery, ignoreCase = true)

            fun matchesVisibility(storeItem: StoreItem, mode: DoneVisibilityMode): Boolean = when {
                isDragging -> true
                storeItem.item.state != ItemState.DEACTIVATED -> true
                mode == DoneVisibilityMode.ALL -> true
                mode == DoneVisibilityMode.RECENT -> {
                    val changedAt = storeItem.item.stateChangedAt
                    changedAt != null && changedAt >= recentThreshold
                }

                else -> false
            }

            fun matchesAvailability(storeItem: StoreItem): Boolean =
                currentStore == null || currentStore == 0L || storeItem.isAvailable

            var result = allItems.filter { storeItem ->
                matchesSearch(storeItem) && matchesVisibility(storeItem, visibilityMode) && matchesAvailability(
                    storeItem
                )
            }

            if (result.isEmpty() && query.isNotBlank() && visibilityMode != DoneVisibilityMode.ALL) {
                result = allItems.filter { storeItem ->
                    matchesSearch(storeItem) && matchesVisibility(
                        storeItem,
                        DoneVisibilityMode.ALL
                    ) && matchesAvailability(storeItem)
                }
            }

            value = result
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

    private fun normalizeForSearch(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(COMBINING_MARKS_REGEX, "")
    }

    private fun normalizeImportName(text: String): String {
        return normalizeForSearch(text).trim()
    }

    private fun parseImportLines(text: String): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        text.lines()
            .map { it.trim() }
            .map { line ->
                var stripped = line
                while (stripped.startsWith("-") || stripped.startsWith("*")) {
                    stripped = stripped.drop(1).trimStart()
                }
                stripped
            }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val normalized = normalizeImportName(line)
                if (normalized.isNotBlank() && seen.add(normalized.lowercase())) {
                    result.add(line)
                }
            }
        return result
    }

    private fun historyDays(): Int = _historyDays.value ?: 183

    private fun mistakeDoneSeconds(): Int = _mistakeDoneSeconds.value ?: 20

    private fun reactivateMinSeconds(): Int = _reactivateMinSeconds.value ?: 3600

    fun hasExactMatch(query: String): Boolean {
        val allItems = items.value ?: return false
        val normalizedQuery = normalizeForSearch(query)
        return allItems.any { normalizeForSearch(it.item.name).equals(normalizedQuery, ignoreCase = true) }
    }

    fun hasExactMatchRaw(query: String): Boolean {
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
        val current = _doneVisibilityMode.value ?: DoneVisibilityMode.RECENT
        _doneVisibilityMode.value = current.next()
    }

    fun setDoneVisibilityMode(mode: DoneVisibilityMode) {
        _doneVisibilityMode.value = mode
    }

    fun setRecentDoneMinutes(minutes: Int) {
        _recentDoneMinutes.value = minutes
    }

    fun setHistoryDays(days: Int) {
        _historyDays.value = days
    }

    fun setMistakeDoneSeconds(seconds: Int) {
        _mistakeDoneSeconds.value = seconds
    }

    fun setReactivateMinSeconds(seconds: Int) {
        _reactivateMinSeconds.value = seconds
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
            val result = repository.addItem(name.trim(), historyDays())
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
            repository.setItemState(
                itemId,
                ItemState.ACTIVATED,
                historyDays(),
                mistakeDoneSeconds(),
                reactivateMinSeconds()
            )
        }
    }

    fun deactivateItem(itemId: Long) {
        viewModelScope.launch {
            repository.setItemState(
                itemId,
                ItemState.DEACTIVATED,
                historyDays(),
                mistakeDoneSeconds(),
                reactivateMinSeconds()
            )
        }
    }

    fun postponeItem(itemId: Long) {
        viewModelScope.launch {
            repository.setItemState(
                itemId,
                ItemState.POSTPONED,
                historyDays(),
                mistakeDoneSeconds(),
                reactivateMinSeconds()
            )
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

    fun toggleItemAvailability(itemId: Long, currentlyAvailable: Boolean, onResult: (Result<Unit>) -> Unit) {
        val storeId = _currentStoreId.value
        if (storeId == null || storeId == 0L) {
            onResult(Result.failure(IllegalStateException("No store selected")))
            return
        }
        viewModelScope.launch {
            val result = repository.setItemAvailability(storeId, itemId, !currentlyAvailable)
            onResult(result)
        }
    }

    fun updateItemAmount(itemId: Long, amount: Int, onResult: (Result<Unit>) -> Unit) {
        val clamped = amount.coerceAtLeast(1)
        viewModelScope.launch {
            val result = repository.updateItemAmount(itemId, clamped)
            onResult(result)
        }
    }

    fun isStoreSelected(): Boolean {
        val storeId = _currentStoreId.value
        return storeId != null && storeId != 0L
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

    fun getImportPreview(text: String, onResult: (Result<ImportPreview>) -> Unit) {
        viewModelScope.launch {
            val lines = parseImportLines(text)
            if (lines.isEmpty()) {
                onResult(Result.failure(Exception("No items to import")))
                return@launch
            }

            val existingResult = repository.getAllItemNames()
            existingResult.onSuccess { existingNames ->
                val normalizedExisting = existingNames.map { normalizeImportName(it).lowercase() }.toSet()
                val existingItems = mutableListOf<String>()
                val newItems = mutableListOf<String>()
                val orderedItems = mutableListOf<ImportPreviewItem>()
                lines.forEach { line ->
                    val normalized = normalizeImportName(line).lowercase()
                    if (normalizedExisting.contains(normalized)) {
                        existingItems.add(line)
                        orderedItems.add(ImportPreviewItem(line, true))
                    } else {
                        newItems.add(line)
                        orderedItems.add(ImportPreviewItem(line, false))
                    }
                }
                onResult(Result.success(ImportPreview(orderedItems, existingItems, newItems)))
            }.onFailure { error ->
                onResult(Result.failure(error))
            }
        }
    }

    fun importItems(names: List<String>, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val cleaned = names.map { it.trim() }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) {
                onResult(Result.failure(Exception("No items to import")))
                return@launch
            }

            val result = repository.bulkAddItems(cleaned, historyDays())
            onResult(result)
        }
    }

    fun activateItemsByNames(names: List<String>, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val normalized = names.asSequence()
                .map { normalizeImportName(it) }
                .filter { it.isNotBlank() }
                .map { it.lowercase() }
                .distinct()
                .toList()
            if (normalized.isEmpty()) {
                onResult(Result.failure(IllegalArgumentException("No items to activate")))
                return@launch
            }

            val lookupResult = repository.getItemIdsByNames(normalized)
            lookupResult.onSuccess { idMap ->
                val ids = normalized.mapNotNull { idMap[it] }.distinct()
                if (ids.isEmpty()) {
                    onResult(Result.failure(IllegalArgumentException("No matching items to activate")))
                    return@onSuccess
                }

                for (id in ids) {
                    val result = repository.setItemState(
                        id,
                        ItemState.ACTIVATED,
                        historyDays(),
                        mistakeDoneSeconds(),
                        reactivateMinSeconds()
                    )
                    if (result.isFailure) {
                        onResult(Result.failure(result.exceptionOrNull() ?: IllegalStateException("Activation failed")))
                        return@onSuccess
                    }
                }
                onResult(Result.success(Unit))
            }.onFailure { error ->
                onResult(Result.failure(error))
            }
        }
    }

    fun importItems(text: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val lines = parseImportLines(text)
            if (lines.isEmpty()) {
                onResult(Result.failure(Exception("No items to import")))
                return@launch
            }

            val result = repository.bulkAddItems(lines, historyDays())
            onResult(result)
        }
    }

    fun activateAll(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.setAllItemsToState(
                ItemState.ACTIVATED,
                historyDays(),
                mistakeDoneSeconds(),
                reactivateMinSeconds()
            )
            onResult(result)
        }
    }

    fun deactivateAll(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.setAllItemsToState(
                ItemState.DEACTIVATED,
                historyDays(),
                mistakeDoneSeconds(),
                reactivateMinSeconds()
            )
            onResult(result)
        }
    }
}
