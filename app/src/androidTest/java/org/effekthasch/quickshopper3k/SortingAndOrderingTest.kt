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
class SortingAndOrderingTest {
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
    fun items_sortedByIdByDefault() = runTest {
        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        val items = repository.getAllItems().first()

        assertEquals(milk, items[0].id)
        assertEquals(bread, items[1].id)
        assertEquals(eggs, items[2].id)
    }

    @Test
    fun items_newerItemsHaveHigherId() = runTest {
        val first = repository.addItem("First").getOrThrow()
        Thread.sleep(10)
        val second = repository.addItem("Second").getOrThrow()

        assertTrue(second > first)
    }

    @Test
    fun defaultStoreOrdering_maintainsInsertionOrder() = runTest {
        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val items = repository.getItemsForStore(0L).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Eggs", items[2].item.name)
    }

    @Test
    fun customStoreOrdering_overridesDefault() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                eggs to 1,
                milk to 2,
                bread to 3
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Eggs", items[0].item.name)
        assertEquals("Milk", items[1].item.name)
        assertEquals("Bread", items[2].item.name)
    }

    @Test
    fun ordering_nullOrdersSortedByIdFirst() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val item1 = repository.addItem("Item1").getOrThrow()
        val item2 = repository.addItem("Item2").getOrThrow()
        val item3 = repository.addItem("Item3").getOrThrow()
        val item4 = repository.addItem("Item4").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                item3 to 1,
                item4 to 2
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Item1", items[0].item.name)
        assertEquals("Item2", items[1].item.name)
        assertEquals("Item3", items[2].item.name)
        assertEquals("Item4", items[3].item.name)
    }

    @Test
    fun ordering_duplicateSortIndices_sortedById() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val item1 = repository.addItem("Item1").getOrThrow()
        val item2 = repository.addItem("Item2").getOrThrow()
        val item3 = repository.addItem("Item3").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                item1 to 1,
                item2 to 1,
                item3 to 2
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Item1", items[0].item.name)
        assertEquals("Item2", items[1].item.name)
        assertEquals("Item3", items[2].item.name)
    }

    @Test
    fun ordering_reordering_updatesCorrectly() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 2,
                eggs to 3
            )
        )

        repository.updateStoreItemOrders(
            storeId, listOf(
                eggs to 1,
                bread to 2,
                milk to 3
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Eggs", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Milk", items[2].item.name)
    }

    @Test
    fun ordering_partialReordering_preservesUnchanged() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 2,
                eggs to 3
            )
        )

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 10
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Bread", items[0].item.name)
        assertEquals("Eggs", items[1].item.name)
        assertEquals("Milk", items[2].item.name)
    }

    @Test
    fun ordering_newItemsDefault_sortBeforeOrdered() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 2
            )
        )

        repository.addItem("Eggs")

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Eggs", items[0].item.name)
        assertEquals("Milk", items[1].item.name)
        assertEquals("Bread", items[2].item.name)
    }

    @Test
    fun ordering_stateDoesNotAffectOrder() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 2,
                eggs to 3
            )
        )

        repository.setItemState(bread, ItemState.DEACTIVATED)
        repository.setItemState(eggs, ItemState.POSTPONED)

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Eggs", items[2].item.name)
    }

    @Test
    fun ordering_availabilityDoesNotAffectOrder() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 2,
                eggs to 3
            )
        )

        repository.setItemAvailability(storeId, bread, false)

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Eggs", items[2].item.name)
    }

    @Test
    fun ordering_storeSpecific_independent() = runTest {
        val store1 = repository.addStore("Store 1").getOrThrow()
        val store2 = repository.addStore("Store 2").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()

        repository.updateStoreItemOrders(
            store1, listOf(
                milk to 1,
                bread to 2
            )
        )

        repository.updateStoreItemOrders(
            store2, listOf(
                bread to 1,
                milk to 2
            )
        )

        val items1 = repository.getItemsForStore(store1).first()
        assertEquals("Milk", items1[0].item.name)
        assertEquals("Bread", items1[1].item.name)

        val items2 = repository.getItemsForStore(store2).first()
        assertEquals("Bread", items2[0].item.name)
        assertEquals("Milk", items2[1].item.name)
    }

    @Test
    fun ordering_zeroSortIndex_valid() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 0,
                bread to 1
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
    }

    @Test
    fun ordering_negativeSortIndex_valid() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to -1,
                bread to 1
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
    }

    @Test
    fun ordering_largeGaps_maintainOrder() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        val bread = repository.addItem("Bread").getOrThrow()
        val eggs = repository.addItem("Eggs").getOrThrow()

        repository.updateStoreItemOrders(
            storeId, listOf(
                milk to 1,
                bread to 100,
                eggs to 10000
            )
        )

        val items = repository.getItemsForStore(storeId).first()

        assertEquals("Milk", items[0].item.name)
        assertEquals("Bread", items[1].item.name)
        assertEquals("Eggs", items[2].item.name)
    }
}
