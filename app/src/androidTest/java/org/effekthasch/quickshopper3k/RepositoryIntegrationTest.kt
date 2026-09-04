package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ShoppingRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun addItem_persistsAndRetrievesCorrectly() = runTest {
        val result = repository.addItem("Milk")
        assertTrue(result.isSuccess)

        val items = repository.getAllItems().first()
        assertEquals(1, items.size)
        assertEquals("Milk", items[0].name)
        assertEquals(ItemState.ACTIVATED, items[0].state)
    }

    @Test
    fun addMultipleItems_preservesInsertionOrder() = runTest {
        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val items = repository.getAllItems().first()
        assertEquals(3, items.size)
        assertEquals("Milk", items[0].name)
        assertEquals("Bread", items[1].name)
        assertEquals("Eggs", items[2].name)
    }

    @Test
    fun setItemState_updatesStateCorrectly() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemState(itemId, ItemState.DEACTIVATED)
        var items = repository.getAllItems().first()
        assertEquals(ItemState.DEACTIVATED, items[0].state)

        repository.setItemState(itemId, ItemState.POSTPONED)
        items = repository.getAllItems().first()
        assertEquals(ItemState.POSTPONED, items[0].state)

        repository.setItemState(itemId, ItemState.ACTIVATED)
        items = repository.getAllItems().first()
        assertEquals(ItemState.ACTIVATED, items[0].state)
    }

    @Test
    fun updateItemName_changesNameCorrectly() = runTest {
        val itemId = repository.addItem("Mlk").getOrThrow()

        val result = repository.updateItemName(itemId, "Milk")
        assertTrue(result.isSuccess)

        val items = repository.getAllItems().first()
        assertEquals("Milk", items[0].name)
    }

    @Test
    fun updateItem_changesIndependentTextFields() = runTest {
        val itemId = repository.addItem("Tofu").getOrThrow()

        val result = repository.updateItem(itemId, "Tofu", "smoked", "bean curd")
        assertTrue(result.isSuccess)

        val item = repository.getAllItems().first().single()
        assertEquals("Tofu", item.name)
        assertEquals("smoked", item.description)
        assertEquals("bean curd", item.synonyms)
    }

    @Test
    fun deleteItem_removesFromDatabase() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()

        val result = repository.deleteItem(itemId)
        assertTrue(result.isSuccess)

        val items = repository.getAllItems().first()
        assertEquals(0, items.size)
    }

    @Test
    fun addStore_persistsAndRetrievesCorrectly() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("Aldi", stores[0].name)

        val store = repository.getStoreById(storeId)
        assertNotNull(store)
        assertEquals("Aldi", store?.name)
    }

    @Test
    fun updateStoreName_changesNameCorrectly() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        val result = repository.updateStoreName(storeId, "Aldi Süd")
        assertTrue(result.isSuccess)

        val store = repository.getStoreById(storeId)
        assertEquals("Aldi Süd", store?.name)
    }

    @Test
    fun deleteStore_removesStoreAndRelations() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        val result = repository.deleteStore(storeId)
        assertTrue(result.isSuccess)

        val stores = repository.getAllStores().first()
        assertEquals(0, stores.size)
    }

    @Test
    fun itemAvailability_defaultsToAvailable() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        repository.addItem("Milk")

        val items = repository.getItemsForStore(storeId).first()
        assertEquals(1, items.size)
        assertTrue(items[0].isAvailable)
    }

    @Test
    fun setItemAvailability_marksItemAsUnavailable() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(storeId, itemId, false)

        val items = repository.getItemsForStore(storeId).first()
        assertFalse(items[0].isAvailable)
    }

    @Test
    fun storeOrdering_sortsNullOrdersFirst() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        repository.addItem("Milk")
        val item2 = repository.addItem("Bread").getOrThrow()
        repository.addItem("Eggs")

        repository.updateStoreItemOrders(storeId, listOf(item2 to 1))

        val items = repository.getItemsForStore(storeId).first()
        assertEquals(3, items.size)
        assertEquals("Milk", items[0].item.name)
        assertEquals("Eggs", items[1].item.name)
        assertEquals("Bread", items[2].item.name)
    }

    @Test
    fun storeOrdering_respectsExplicitOrder() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        val item1 = repository.addItem("Milk").getOrThrow()
        val item2 = repository.addItem("Bread").getOrThrow()
        val item3 = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                item3 to 1,
                item1 to 2,
                item2 to 3
            )
        )

        val items = repository.getItemsForStore(storeId).first()
        assertEquals("Eggs", items[0].item.name)
        assertEquals("Milk", items[1].item.name)
        assertEquals("Bread", items[2].item.name)
    }

    @Test
    fun storeOrdering_fallsBackToDefaultStoreOrder() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Eggs", items[2].item.name)
    }

    @Test
    fun multipleStores_maintainIndependentOrdering() = runTest {
        val store1 = repository.addStore("Aldi").getOrThrow()
        val store2 = repository.addStore("Rewe").getOrThrow()

        val item1 = repository.addItem("Milk").getOrThrow()
        val item2 = repository.addItem("Bread").getOrThrow()

        repository.updateStoreItemOrders(store1, listOf(item2 to 1, item1 to 2))
        repository.updateStoreItemOrders(store2, listOf(item1 to 1, item2 to 2))

        val itemsStore1 = repository.getItemsForStore(store1).first()
        assertEquals("Bread", itemsStore1[0].item.name)
        assertEquals("Milk", itemsStore1[1].item.name)

        val itemsStore2 = repository.getItemsForStore(store2).first()
        assertEquals("Milk", itemsStore2[0].item.name)
        assertEquals("Bread", itemsStore2[1].item.name)
    }

    @Test
    fun multipleStores_maintainIndependentAvailability() = runTest {
        val store1 = repository.addStore("Aldi").getOrThrow()
        val store2 = repository.addStore("Rewe").getOrThrow()

        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(store1, itemId, false)

        val itemsStore1 = repository.getItemsForStore(store1).first()
        assertFalse(itemsStore1[0].isAvailable)

        val itemsStore2 = repository.getItemsForStore(store2).first()
        assertTrue(itemsStore2[0].isAvailable)
    }
}
