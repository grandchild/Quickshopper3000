package org.effekthasch.quickshopper3k.ui.stores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.util.PreferencesManager

class StoresViewModelFactory(
    private val repository: ShoppingRepository,
    private val preferencesManager: PreferencesManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StoresViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StoresViewModel(repository, preferencesManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
