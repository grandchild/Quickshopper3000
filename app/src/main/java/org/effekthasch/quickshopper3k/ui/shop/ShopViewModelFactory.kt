package org.effekthasch.quickshopper3k.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository

class ShopViewModelFactory(
    private val repository: ShoppingRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ShopViewModel::class.java)) {
            return ShopViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
