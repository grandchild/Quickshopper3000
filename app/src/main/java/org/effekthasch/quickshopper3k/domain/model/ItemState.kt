package org.effekthasch.quickshopper3k.domain.model

enum class ItemState {
    ACTIVATED,
    DEACTIVATED,
    POSTPONED;

    companion object {
        fun fromString(value: String): ItemState {
            return when (value.uppercase()) {
                "ACTIVATED" -> ACTIVATED
                "DEACTIVATED" -> DEACTIVATED
                "POSTPONED" -> POSTPONED
                else -> ACTIVATED
            }
        }
    }

    override fun toString(): String = name
}
