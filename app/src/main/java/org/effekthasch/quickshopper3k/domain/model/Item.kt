package org.effekthasch.quickshopper3k.domain.model

data class Item(
    val id: Long,
    val name: String,
    val state: ItemState,
    val createdAt: Long
)
