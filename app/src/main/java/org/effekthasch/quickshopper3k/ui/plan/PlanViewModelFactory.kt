package org.effekthasch.quickshopper3k.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository

class PlanViewModelFactory(
    private val repository: ShoppingRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlanViewModel::class.java)) {
            return PlanViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
