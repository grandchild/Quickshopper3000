package org.effekthasch.quickshopper3k.domain.model

data class Item(
    val id: Long,
    val uuid: String,
    val name: String,
    val state: ItemState,
    val createdAt: Long,
    val stateChangedAt: Long? = null,
    val amount: Int = 1
)
