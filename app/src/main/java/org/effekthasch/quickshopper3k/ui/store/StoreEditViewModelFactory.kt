package org.effekthasch.quickshopper3k.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository

class StoreEditViewModelFactory(
    private val repository: ShoppingRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StoreEditViewModel::class.java)) {
            return StoreEditViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
