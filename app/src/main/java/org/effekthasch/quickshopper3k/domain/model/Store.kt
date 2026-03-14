package org.effekthasch.quickshopper3k.domain.model

data class Store(
    val id: Long,
    val uuid: String,
    val name: String,
    val specialtyStore: Boolean = false
)
