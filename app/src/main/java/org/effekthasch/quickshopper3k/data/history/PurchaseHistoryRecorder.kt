package org.effekthasch.quickshopper3k.data.history

import org.effekthasch.quickshopper3k.data.local.ItemAddHistoryEntity
import org.effekthasch.quickshopper3k.data.local.ItemDao
import org.effekthasch.quickshopper3k.domain.model.ItemState

data class PurchaseHistorySettings(
    val retentionDays: Int,
    val mistakeDoneSeconds: Int,
    val reactivateMinSeconds: Int
)

interface PurchaseHistoryRecorder {
    suspend fun recordActivation(
        itemId: Long,
        at: Long,
        settings: PurchaseHistorySettings
    )

    suspend fun recordStateChange(
        itemId: Long,
        previousState: ItemState,
        previousStateChangedAt: Long?,
        state: ItemState,
        at: Long,
        settings: PurchaseHistorySettings
    )
}

class RoomPurchaseHistoryRecorder(
    private val itemDao: ItemDao
) : PurchaseHistoryRecorder {
    override suspend fun recordActivation(
        itemId: Long,
        at: Long,
        settings: PurchaseHistorySettings
    ) {
        itemDao.insertItemAddHistory(ItemAddHistoryEntity(itemId = itemId, addedAt = at))
        val days = if (settings.retentionDays <= 0) 1 else settings.retentionDays
        itemDao.deleteItemAddHistoryOlderThan(at - days * 24 * 60 * 60 * 1000L)
    }

    override suspend fun recordStateChange(
        itemId: Long,
        previousState: ItemState,
        previousStateChangedAt: Long?,
        state: ItemState,
        at: Long,
        settings: PurchaseHistorySettings
    ) {
        when {
            state == ItemState.ACTIVATED && previousState == ItemState.DEACTIVATED &&
                    (previousStateChangedAt == null ||
                            at - previousStateChangedAt > settings.reactivateMinSeconds * 1000L) -> {
                recordActivation(itemId, at, settings)
            }

            state == ItemState.DEACTIVATED && previousState != ItemState.DEACTIVATED -> {
                val latest = itemDao.getLatestItemAddHistory(itemId)
                if (latest != null && at - latest.addedAt <= settings.mistakeDoneSeconds * 1000L) {
                    itemDao.deleteItemAddHistoryById(latest.id)
                }
            }
        }
    }
}
