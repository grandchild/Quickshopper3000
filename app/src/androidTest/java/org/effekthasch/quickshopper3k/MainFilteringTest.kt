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
class MainFilteringTest {
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

    private fun applyFiltering(
        items: List<org.effekthasch.quickshopper3k.domain.model.StoreItem>,
        searchQuery: String = "",
        showDoneItems: Boolean = false,
        isDragging: Boolean = false
    ): List<org.effekthasch.quickshopper3k.domain.model.StoreItem> {
        return items.filter { storeItem ->
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                storeItem.item.name.contains(searchQuery, ignoreCase = true)
            }

            val matchesVisibility = if (showDoneItems || isDragging) {
                true
            } else {
                storeItem.item.state != ItemState.DEACTIVATED
            }

            matchesSearch && matchesVisibility
        }
    }

    @Test
    fun filteredItems_noSearch_showsAllActivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items)

        assertEquals(3, filtered.size)
    }

    @Test
    fun filteredItems_searchQuery_filtersItems() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Chocolate Milk")
        repository.addItem("Bread")

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, searchQuery = "milk")

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.item.name.contains("Milk", ignoreCase = true) })
    }

    @Test
    fun filteredItems_searchQuery_caseInsensitive() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("milk")
        repository.addItem("MILK")
        repository.addItem("Bread")

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, searchQuery = "MILK")

        assertEquals(2, filtered.size)
    }

    @Test
    fun filteredItems_emptySearch_showsAll() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Bread")

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, searchQuery = "")

        assertEquals(2, filtered.size)
    }

    @Test
    fun filteredItems_hidesDeactivatedByDefault() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        val bread = repository.addItem("Bread").getOrThrow()
        repository.setItemState(bread, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items)

        assertEquals(1, filtered.size)
        assertEquals("Milk", filtered[0].item.name)
    }

    @Test
    fun filteredItems_showDoneItems_showsDeactivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        val bread = repository.addItem("Bread").getOrThrow()
        repository.setItemState(bread, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, showDoneItems = true)

        assertEquals(2, filtered.size)
    }

    @Test
    fun filteredItems_showsPostponedItems() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        repository.setItemState(milk, ItemState.POSTPONED)

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items)

        assertEquals(1, filtered.size)
        assertEquals(ItemState.POSTPONED, filtered[0].item.state)
    }

    @Test
    fun filteredItems_searchAndVisibility_combined() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        val bread = repository.addItem("Bread").getOrThrow()
        repository.addItem("Eggs")
        repository.setItemState(bread, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, searchQuery = "e")

        assertEquals(1, filtered.size)
        assertEquals("Eggs", filtered[0].item.name)
    }

    @Test
    fun filteredItems_searchWithDeactivated_showsWhenEnabled() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        val chocolateMilk = repository.addItem("Chocolate Milk").getOrThrow()
        repository.setItemState(chocolateMilk, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()

        var filtered = applyFiltering(items, searchQuery = "milk")
        assertEquals(1, filtered.size)

        filtered = applyFiltering(items, searchQuery = "milk", showDoneItems = true)
        assertEquals(2, filtered.size)
    }

    @Test
    fun filteredItems_dragging_showsDeactivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        val bread = repository.addItem("Bread").getOrThrow()
        repository.setItemState(bread, ItemState.DEACTIVATED)

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, isDragging = true)

        assertEquals(2, filtered.size)
    }

    @Test
    fun filteredItems_storeChange_updatesFiltering() = runTest {
        val store1 = repository.addStore("Store 1").getOrThrow()
        val store2 = repository.addStore("Store 2").getOrThrow()

        val milk = repository.addItem("Milk").getOrThrow()
        repository.setItemAvailability(store1, milk, false)

        val items1 = repository.getItemsForStore(store1).first()
        assertFalse(items1[0].isAvailable)

        val items2 = repository.getItemsForStore(store2).first()
        assertTrue(items2[0].isAvailable)
    }

    @Test
    fun filteredItems_noMatch_returnsEmpty() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Bread")

        val items = repository.getItemsForStore(storeId).first()
        val filtered = applyFiltering(items, searchQuery = "xyz123")

        assertEquals(0, filtered.size)
    }
}
