package org.effekthasch.quickshopper3k.ui.stores

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.util.PreferencesManager

class StoresViewModel(
    private val repository: ShoppingRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _currentStoreId = MutableLiveData<Long?>()

    init {
        _currentStoreId.value = preferencesManager.currentStoreId
    }

    val allStores: LiveData<List<Store>> = repository.getAllStores().asLiveData()

    val currentStore: LiveData<Store?> = _currentStoreId.switchMap { storeId ->
        MutableLiveData<Store?>().apply {
            if (storeId != null) {
                viewModelScope.launch {
                    value = repository.getStoreById(storeId)
                }
            } else {
                value = null
            }
        }
    }

    val storeItems: LiveData<List<StoreItem>> = _currentStoreId.switchMap { storeId ->
        if (storeId != null) {
            repository.getItemsForStore(storeId).map { items ->
                items.sortedWith(
                    compareBy(
                    { it.sortOrder ?: Int.MIN_VALUE },
                    { it.item.id }
                ))
            }.asLiveData()
        } else {
            MutableLiveData(emptyList())
        }
    }

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun selectStore(storeId: Long) {
        preferencesManager.currentStoreId = storeId
        _currentStoreId.value = storeId
    }

    fun addStore(name: String, specialtyStore: Boolean) {
        viewModelScope.launch {
            repository.addStore(name, specialtyStore).fold(
                onSuccess = { storeId ->
                    preferencesManager.currentStoreId = storeId
                    _currentStoreId.value = storeId
                },
                onFailure = { exception ->
                    _error.value = "Failed to add store: ${exception.message}"
                }
            )
        }
    }

    fun updateStoreName(name: String) {
        val storeId = _currentStoreId.value ?: return
        viewModelScope.launch {
            val store = repository.getStoreById(storeId)
            if (store != null) {
                repository.updateStore(storeId, name, store.specialtyStore).fold(
                    onSuccess = {
                        _currentStoreId.value = storeId
                    },
                    onFailure = { exception ->
                        _error.value = "Failed to update store: ${exception.message}"
                    }
                )
            }
        }
    }

    fun deleteStore(storeId: Long) {
        viewModelScope.launch {
            repository.deleteStore(storeId).fold(
                onSuccess = {
                    preferencesManager.currentStoreId = null
                    _currentStoreId.value = null
                },
                onFailure = { exception ->
                    _error.value = "Failed to delete store: ${exception.message}"
                }
            )
        }
    }

    fun setItemAvailability(itemId: Long, isAvailable: Boolean) {
        val storeId = _currentStoreId.value ?: return
        viewModelScope.launch {
            repository.setItemAvailability(storeId, itemId, isAvailable).fold(
                onSuccess = {},
                onFailure = { exception ->
                    _error.value = "Failed to update availability: ${exception.message}"
                }
            )
        }
    }

    fun updateItemOrders(storeId: Long, orders: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateStoreItemOrders(storeId, orders).fold(
                onSuccess = {},
                onFailure = { exception ->
                    _error.value = "Failed to update order: ${exception.message}"
                }
            )
        }
    }
}
