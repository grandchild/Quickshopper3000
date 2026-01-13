package org.effekthasch.quickshopper3k.domain.model

data class StoreItem(
    val item: Item,
    val sortOrder: Int?,
    val isAvailable: Boolean
)
