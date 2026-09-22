package org.effekthasch.quickshopper3k.data.repository

import androidx.room.withTransaction
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.local.ItemEntity
import org.effekthasch.quickshopper3k.data.local.StoreItemOrderEntity
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.util.Ulid

class ExampleItemsSeeder(private val database: AppDatabase) {
    suspend fun seedIfEmpty(): Boolean = database.withTransaction {
        val itemDao = database.itemDao()
        val storeDao = database.storeDao()
        if (itemDao.getAllItems().isNotEmpty()) return@withTransaction false

        val now = System.currentTimeMillis()
        val ids = itemDao.insertItems(
            examples.map { example ->
                ItemEntity(
                    uuid = Ulid.generate(),
                    name = example.name,
                    description = example.description,
                    synonyms = "",
                    createdAt = now,
                    amount = example.amount
                )
            }
        )

        ids.zip(examples).forEachIndexed { index, (itemId, example) ->
            storeDao.insertOrUpdateItemOrder(
                StoreItemOrderEntity(
                    storeId = ShoppingRepository.DEFAULT_STORE_ID,
                    itemId = itemId,
                    sortOrder = index + 1
                )
            )
            if (example.state != ItemState.ACTIVATED) {
                itemDao.setItemState(itemId, example.state.toString())
            }
        }
        true
    }

    private data class ExampleItem(
        val name: String,
        val description: String = "",
        val amount: Int = 1,
        val state: ItemState = ItemState.ACTIVATED
    )

    private companion object {
        val examples = listOf(
            ExampleItem("Apples", "Golden Delicious"),
            ExampleItem("Oranges", amount = 3),
            ExampleItem("Bananas", state = ItemState.DEACTIVATED),
            ExampleItem("Pineapple", state = ItemState.POSTPONED)
        )
    }
}
