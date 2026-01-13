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
class FilteringAndAvailabilityTest {
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
    fun storeFiltering_showsOnlyAvailableItems() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()

        repository.addItem("Milk")
        val bread = repository.addItem("Bread").getOrThrow()
        repository.addItem("Eggs")

        repository.setItemAvailability(storeId, bread, false)

        val items = repository.getItemsForStore(storeId).first()

        assertEquals(3, items.size)
        assertTrue(items.find { it.item.name == "Milk" }?.isAvailable == true)
        assertFalse(items.find { it.item.name == "Bread" }?.isAvailable == true)
        assertTrue(items.find { it.item.name == "Eggs" }?.isAvailable == true)
    }

    @Test
    fun storeFiltering_multipleStoresIndependent() = runTest {
        val aldi = repository.addStore("Aldi").getOrThrow()
        val rewe = repository.addStore("Rewe").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()

        repository.setItemAvailability(aldi, milk, false)
        repository.setItemAvailability(rewe, bread, false)

        val aldiItems = repository.getItemsForStore(aldi).first()
        assertFalse(aldiItems.find { it.item.name == "Milk" }?.isAvailable == true)
        assertTrue(aldiItems.find { it.item.name == "Bread" }?.isAvailable == true)

        val reweItems = repository.getItemsForStore(rewe).first()
        assertTrue(reweItems.find { it.item.name == "Milk" }?.isAvailable == true)
        assertFalse(reweItems.find { it.item.name == "Bread" }?.isAvailable == true)
    }

    @Test
    fun availability_togglesCorrectly() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(storeId, itemId, false)
        var items = repository.getItemsForStore(storeId).first()
        assertFalse(items[0].isAvailable)

        repository.setItemAvailability(storeId, itemId, true)
        items = repository.getItemsForStore(storeId).first()
        assertTrue(items[0].isAvailable)
    }

    @Test
    fun availability_defaultsToAvailableForNewStore() = runTest {
        val store1 = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(store1, itemId, false)

        val store2 = repository.addStore("Rewe").getOrThrow()

        val items1 = repository.getItemsForStore(store1).first()
        assertFalse(items1[0].isAvailable)

        val items2 = repository.getItemsForStore(store2).first()
        assertTrue(items2[0].isAvailable)
    }

    @Test
    fun availability_persistsAcrossStateChanges() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(storeId, itemId, false)
        repository.setItemState(itemId, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()
        assertFalse(items[0].isAvailable)
        assertEquals(ItemState.DEACTIVATED, items[0].item.state)
    }

    @Test
    fun stateFiltering_byState() = runTest {
        val item1 = repository.addItem("Milk").getOrThrow()
        val item2 = repository.addItem("Bread").getOrThrow()
        val item3 = repository.addItem("Eggs").getOrThrow()

        repository.setItemState(item1, ItemState.ACTIVATED)
        repository.setItemState(item2, ItemState.DEACTIVATED)
        repository.setItemState(item3, ItemState.POSTPONED)

        val allItems = repository.getAllItems().first()

        val activated = allItems.filter { it.state == ItemState.ACTIVATED }
        val deactivated = allItems.filter { it.state == ItemState.DEACTIVATED }
        val postponed = allItems.filter { it.state == ItemState.POSTPONED }

        assertEquals(1, activated.size)
        assertEquals(1, deactivated.size)
        assertEquals(1, postponed.size)
    }

    @Test
    fun complexScenario_multipleStoresStatesAvailability() = runTest {
        val aldi = repository.addStore("Aldi").getOrThrow()
        val rewe = repository.addStore("Rewe").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.setItemState(milk, ItemState.ACTIVATED)
        repository.setItemState(bread, ItemState.DEACTIVATED)
        repository.setItemState(eggs, ItemState.POSTPONED)

        repository.setItemAvailability(aldi, milk, false)
        repository.setItemAvailability(rewe, eggs, false)

        val aldiItems = repository.getItemsForStore(aldi).first()
        assertEquals(3, aldiItems.size)
        assertFalse(aldiItems.find { it.item.name == "Milk" }?.isAvailable == true)
        assertTrue(aldiItems.find { it.item.name == "Bread" }?.isAvailable == true)
        assertTrue(aldiItems.find { it.item.name == "Eggs" }?.isAvailable == true)

        val reweItems = repository.getItemsForStore(rewe).first()
        assertEquals(3, reweItems.size)
        assertTrue(reweItems.find { it.item.name == "Milk" }?.isAvailable == true)
        assertTrue(reweItems.find { it.item.name == "Bread" }?.isAvailable == true)
        assertFalse(reweItems.find { it.item.name == "Eggs" }?.isAvailable == true)
    }

    @Test
    fun deletedStore_removesAvailabilityData() = runTest {
        val store1 = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemAvailability(store1, itemId, false)
        repository.deleteStore(store1)

        val store2 = repository.addStore("NewStore").getOrThrow()
        val items = repository.getItemsForStore(store2).first()

        assertTrue(items[0].isAvailable)
    }

    @Test
    fun deletedItem_removesFromAllStores() = runTest {
        val aldi = repository.addStore("Aldi").getOrThrow()
        val rewe = repository.addStore("Rewe").getOrThrow()

        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemAvailability(aldi, itemId, false)

        repository.deleteItem(itemId)

        val aldiItems = repository.getItemsForStore(aldi).first()
        val reweItems = repository.getItemsForStore(rewe).first()

        assertEquals(0, aldiItems.size)
        assertEquals(0, reweItems.size)
    }
}
