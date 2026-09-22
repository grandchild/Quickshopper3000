package org.effekthasch.quickshopper3k.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemIdentity
import org.effekthasch.quickshopper3k.domain.model.ItemInput
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.ItemText
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.domain.statistics.PurchaseInsights
import org.effekthasch.quickshopper3k.domain.statistics.PurchaseStatistics
import org.effekthasch.quickshopper3k.util.ItemVisibilityMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(private val repository: ShoppingRepository) : ViewModel() {
    data class ImportPreviewItem(
        val item: ItemInput,
        val isExisting: Boolean
    )

    data class ImportPreview(
        val orderedItems: List<ImportPreviewItem>,
        val existingItems: List<ItemInput>,
        val newItems: List<ItemInput>
    )

    private val _currentStoreId = MutableStateFlow<Long?>(null)
    private val _searchQuery = MutableLiveData("")
    private val _doneVisibilityMode = MutableLiveData(ItemVisibilityMode.CURRENT)
    private val _recentlyDoneMinutes = MutableLiveData(60)
    private val _historyDays = MutableLiveData(ShoppingRepository.DEFAULT_HISTORY_DAYS)
    private val _mistakeDoneSeconds = MutableLiveData(20)
    private val _reactivateMinSeconds = MutableLiveData(3600)
    private val _flushRecentlyDoneAt = MutableLiveData<Long?>(null)
    private val _isDragging = MutableLiveData(false)
    private val _forecastNow = MutableStateFlow(System.currentTimeMillis())
    private var resetInFlight = false
    private var cachedSearchItems: List<StoreItem>? = null
    private var searchableTextByItemId = emptyMap<Long, ItemText.SearchableText>()

    val stores: LiveData<List<Store>> = repository.getAllStores().asLiveData()

    val items: LiveData<List<StoreItem>> = _currentStoreId.flatMapLatest { storeId ->
        repository.getItemsForStore(storeId ?: 0L)
    }.asLiveData()

    val purchaseInsights: LiveData<PurchaseInsights> = combine(
        repository.getPurchaseHistories(),
        _forecastNow
    ) { histories, now ->
        withContext(Dispatchers.Default) {
            PurchaseInsights(
                histories = histories.associate { it.itemId to it.activations },
                forecasts = PurchaseStatistics.forecast(histories, now)
            )
        }
    }.asLiveData()

    fun activeItemsForSharing(): List<StoreItem> {
        return items.value.orEmpty().filter { it.item.state == ItemState.ACTIVATED }
    }

    val filteredItems: LiveData<List<StoreItem>> = MediatorLiveData<List<StoreItem>>().apply {
        fun update() {
            val allItems = items.value ?: emptyList()
            val query = _searchQuery.value ?: ""
            if (cachedSearchItems !== allItems) {
                searchableTextByItemId = allItems.associate { storeItem ->
                    storeItem.item.id to ItemText.searchableText(storeItem.item)
                }
                cachedSearchItems = allItems
            }
            val searchTerms = ItemText.compileSearchTerms(query)
            val visibilityMode = _doneVisibilityMode.value ?: ItemVisibilityMode.CURRENT
            val recentMinutes = _recentlyDoneMinutes.value ?: 60
            val flushAt = _flushRecentlyDoneAt.value
            val isDragging = _isDragging.value ?: false
            val currentStore = _currentStoreId.value
            val now = System.currentTimeMillis()
            val minutesThreshold = now - (recentMinutes * 60 * 1000L)
            val recentThreshold = if (flushAt != null && flushAt > minutesThreshold) flushAt else minutesThreshold
            val safetyCutoff = recentThreshold - (60 * 1000L)

            val displayItems = allItems.map { storeItem ->
                val changedAt = storeItem.item.stateChangedAt ?: 0L
                if (storeItem.item.state == ItemState.DEACTIVATED && changedAt < recentThreshold) {
                    storeItem.copy(item = storeItem.item.copy(amount = 1))
                } else {
                    storeItem
                }
            }

            if (!resetInFlight) {
                val candidates = allItems.filter { storeItem ->
                    val changedAt = storeItem.item.stateChangedAt ?: 0L
                    storeItem.item.state == ItemState.DEACTIVATED &&
                            changedAt < safetyCutoff &&
                            storeItem.item.amount != 1
                }
                if (candidates.isNotEmpty()) {
                    resetInFlight = true
                    viewModelScope.launch {
                        try {
                            repository.resetStaleAmounts(safetyCutoff)
                        } finally {
                            resetInFlight = false
                        }
                    }
                }
            }


            fun matchesSearch(storeItem: StoreItem): Boolean {
                val searchableText = searchableTextByItemId[storeItem.item.id]
                    ?: ItemText.searchableText(storeItem.item)
                return ItemText.matches(searchableText, searchTerms)
            }

            fun matchesVisibility(storeItem: StoreItem, mode: ItemVisibilityMode): Boolean = when {
                isDragging -> true
                mode == ItemVisibilityMode.NEXT -> storeItem.item.state == ItemState.DEACTIVATED
                storeItem.item.state != ItemState.DEACTIVATED -> true
                mode == ItemVisibilityMode.ALL -> true
                mode == ItemVisibilityMode.CURRENT -> {
                    val changedAt = storeItem.item.stateChangedAt
                    changedAt != null && changedAt >= recentThreshold
                }

                else -> false
            }

            fun matchesAvailability(storeItem: StoreItem): Boolean =
                currentStore == null || currentStore == 0L || storeItem.isAvailable

            val effectiveMode = if (query.isNotBlank() && visibilityMode != ItemVisibilityMode.ALL)
                ItemVisibilityMode.ALL
            else
                visibilityMode

            val result = if (query.isNotBlank()) {
                displayItems.filter { storeItem ->
                    matchesSearch(storeItem) && matchesVisibility(storeItem, effectiveMode)
                }
            } else {
                displayItems.filter { storeItem ->
                    matchesSearch(storeItem) && matchesVisibility(storeItem, effectiveMode) && matchesAvailability(storeItem)
                }
            }

            value = if (effectiveMode == ItemVisibilityMode.NEXT && query.isBlank()) {
                val forecasts = purchaseInsights.value?.forecasts.orEmpty()
                result.withIndex()
                    .sortedWith(compareByDescending<IndexedValue<StoreItem>> {
                        forecasts[it.value.item.id]?.probability ?: 0.0
                    }.thenBy { it.index })
                    .map { it.value }
            } else {
                result
            }
        }

        addSource(items) { update() }
        addSource(_searchQuery) { update() }
        addSource(_doneVisibilityMode) { update() }
        addSource(_recentlyDoneMinutes) { update() }
        addSource(_flushRecentlyDoneAt) { update() }
        addSource(_isDragging) { update() }
        addSource(_currentStoreId.asLiveData()) { update() }
        addSource(purchaseInsights) { update() }
    }

    val currentStoreId: LiveData<Long?> = _currentStoreId.asLiveData()
    val searchQuery: LiveData<String> = _searchQuery
    val doneVisibilityMode: LiveData<ItemVisibilityMode> = _doneVisibilityMode

    internal fun importIdentity(text: String): ItemIdentity? {
        return ItemText.parseImportLine(text)?.let(ItemText::identity)
    }

    private fun parseImportLines(text: String): List<ItemInput> {
        val seen = mutableSetOf<ItemIdentity>()
        return text.lines()
            .mapNotNull(ItemText::parseImportLine)
            .filter { seen.add(ItemText.identity(it)) }
    }

    private fun historyDays(): Int = _historyDays.value ?: 183

    private fun mistakeDoneSeconds(): Int = _mistakeDoneSeconds.value ?: 20

    private fun reactivateMinSeconds(): Int = _reactivateMinSeconds.value ?: 3600

    fun hasExactMatch(query: String): Boolean = hasBlockingExactName(query)

    fun hasExactMatchRaw(query: String): Boolean = hasBlockingExactName(query)

    private fun hasBlockingExactName(query: String): Boolean {
        return ItemText.blocksQuickAdd(items.value.orEmpty().map { it.item }, query)
    }

    fun setCurrentStore(storeId: Long?) {
        _currentStoreId.value = storeId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun cycleDoneVisibilityMode() {
        val current = _doneVisibilityMode.value ?: ItemVisibilityMode.CURRENT
        _doneVisibilityMode.value = current.next()
    }

    fun setDoneVisibilityMode(mode: ItemVisibilityMode) {
        _doneVisibilityMode.value = mode
    }

    fun setRecentlyDoneMinutes(minutes: Int) {
        _recentlyDoneMinutes.value = minutes
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

    fun refreshForecasts() {
        _forecastNow.value = System.currentTimeMillis()
    }

    fun addItem(name: String, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            val result = repository.addItem(name.trim(), historyDays())
            onResult(result)
        }
    }

    fun updateItem(
        itemId: Long,
        name: String,
        description: String,
        synonyms: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.updateItem(
                itemId,
                name.trim(),
                ItemText.normalizeDescription(description),
                ItemText.normalizeSynonymText(synonyms)
            )
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

    fun recordBought(itemId: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(repository.recordPurchase(itemId, historyDays()))
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

    fun updateItemOrderFromList(finalList: List<StoreItem>, movedItemId: Long? = null, onResult: (Result<Unit>) -> Unit) {
        val storeId = _currentStoreId.value ?: 0L
        val allItems = items.value ?: emptyList()

        if (finalList.isEmpty() || allItems.isEmpty()) {
            onResult(Result.success(Unit))
            return
        }

        val visibleIds = finalList.map { it.item.id }

        val resolvedMovedItemId = movedItemId ?: findMovedItemId(previous = allItems, currentVisible = visibleIds)
        if (resolvedMovedItemId == null) {
            onResult(Result.success(Unit))
            return
        }

        val newVisibleIndex = visibleIds.indexOf(resolvedMovedItemId)
        if (newVisibleIndex < 0) {
            onResult(Result.success(Unit))
            return
        }

        val upperVisibleId = if (newVisibleIndex > 0) visibleIds[newVisibleIndex - 1] else null
        val lowerVisibleId = if (newVisibleIndex + 1 < visibleIds.size) visibleIds[newVisibleIndex + 1] else null

        val sorted = allItems.sortedWith(
            compareBy<StoreItem> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.item.id }
        )
        val sortOrderById = allItems.associate { it.item.id to (it.sortOrder ?: 0) }

        val insertAfterIdx = upperVisibleId?.let { uid -> sorted.indexOfFirst { it.item.id == uid } }
        val insertBeforeIdx = if (insertAfterIdx != null) {
            ((insertAfterIdx + 1) until sorted.size).firstOrNull { sorted[it].item.id != resolvedMovedItemId }
        } else {
            lowerVisibleId?.let { lid -> sorted.indexOfFirst { it.item.id == lid } }
                ?.let { idx -> (idx until sorted.size).firstOrNull { sorted[it].item.id != resolvedMovedItemId } }
        }

        val upperOrder = insertAfterIdx?.let { sortOrderById[sorted[it].item.id] }
        val lowerOrder = insertBeforeIdx?.let { sortOrderById[sorted[it].item.id] }
        val movedCurrent = sortOrderById[resolvedMovedItemId] ?: 0

        val newOrder = computeGappedOrder(upperOrder, lowerOrder, movedCurrent)

        viewModelScope.launch {
            val result = if (newOrder == null) {
                val renumbered = renumberAllWithPowerOfTwoGap(sorted, resolvedMovedItemId, upperVisibleId)
                repository.updateStoreItemOrders(storeId, renumbered)
            } else {
                repository.updateStoreItemOrders(storeId, listOf(resolvedMovedItemId to newOrder))
            }
            onResult(result)
        }
    }

    private fun findMovedItemId(previous: List<StoreItem>, currentVisible: List<Long>): Long? {
        if (currentVisible.size < 2) return null

        val prevVisible = previous.map { it.item.id }.filter { currentVisible.contains(it) }
        if (prevVisible.size != currentVisible.size) return null

        val moved = currentVisible.filter { id ->
            val oldIndex = prevVisible.indexOf(id)
            val newIndex = currentVisible.indexOf(id)
            oldIndex != -1 && oldIndex != newIndex
        }

        return if (moved.size == 1) moved.first() else moved.lastOrNull()
    }

    private fun computeGappedOrder(upper: Int?, lower: Int?, current: Int): Int? {
        val baseGap = 1 shl 10

        return when {
            upper == null && lower == null -> current

            upper == null -> {
                val candidate = lower!! - baseGap
                if (candidate < Int.MIN_VALUE / 2) null else candidate
            }

            lower == null -> {
                val candidate = upper + baseGap
                if (candidate > Int.MAX_VALUE / 2) null else candidate
            }

            else -> {
                val gap = lower - upper
                if (gap <= 1) null else upper + gap / 2
            }
        }
    }

    private fun renumberAllWithPowerOfTwoGap(
        sorted: List<StoreItem>,
        movedItemId: Long,
        upperId: Long?
    ): List<Pair<Long, Int>> {
        val sortedIds = sorted.map { it.item.id }.toMutableList()

        sortedIds.remove(movedItemId)
        val insertAt = if (upperId == null) {
            0
        } else {
            val idx = sortedIds.indexOf(upperId)
            if (idx == -1) 0 else idx + 1
        }
        sortedIds.add(insertAt, movedItemId)

        val gap = 1 shl 10
        return sortedIds.mapIndexed { index, id ->
            id to ((index + 1) * gap)
        }
    }

    fun getAllItemInputs(onResult: (Result<List<ItemInput>>) -> Unit) {
        viewModelScope.launch {
            onResult(repository.getAllItemInputs())
        }
    }

    internal fun normalizeImportItems(items: Collection<ItemInput>): Set<ItemIdentity> {
        return items.map(ItemText::identity).toSet()
    }

    fun buildImportPreview(text: String, normalizedExisting: Set<ItemIdentity>): Result<ImportPreview> {
        val lines = parseImportLines(text)
        if (lines.isEmpty()) {
            return Result.failure(IllegalArgumentException("No items to import"))
        }

        val existingItems = mutableListOf<ItemInput>()
        val newItems = mutableListOf<ItemInput>()
        val orderedItems = mutableListOf<ImportPreviewItem>()

        lines.forEach { item ->
            if (ItemText.identity(item) in normalizedExisting) {
                existingItems.add(item)
                orderedItems.add(ImportPreviewItem(item, true))
            } else {
                newItems.add(item)
                orderedItems.add(ImportPreviewItem(item, false))
            }
        }

        return Result.success(ImportPreview(orderedItems, existingItems, newItems))
    }

    fun importItems(items: List<ItemInput>, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val cleaned = items.map {
                ItemInput(it.name.trim(), ItemText.normalizeDescription(it.description))
            }.filter { it.name.isNotBlank() }
            if (cleaned.isEmpty()) {
                onResult(Result.failure(Exception("No items to import")))
                return@launch
            }

            val result = repository.bulkAddItems(cleaned, historyDays())
            onResult(result)
        }
    }

    fun activateItemsByIdentity(items: List<ItemInput>, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val normalized = items.distinctBy(ItemText::identity)
            if (normalized.isEmpty()) {
                onResult(Result.failure(IllegalArgumentException("No items to activate")))
                return@launch
            }

            val lookupResult = repository.getItemIdsByIdentity(normalized)
            lookupResult.onSuccess { ids ->
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

    fun activateAllPostponed(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.activateAllPostponed()
            onResult(result)
        }
    }
}
