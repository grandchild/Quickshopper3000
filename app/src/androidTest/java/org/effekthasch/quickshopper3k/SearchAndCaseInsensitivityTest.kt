package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class SearchAndCaseInsensitivityTest {
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
    fun itemNames_storedWithOriginalCase() = runTest {
        repository.addItem("MiLk")

        val items = repository.getAllItems().first()
        assertEquals("MiLk", items[0].name)
    }

    @Test
    fun multipleItemsWithDifferentCase_allStored() = runTest {
        repository.addItem("milk")
        repository.addItem("Milk")
        repository.addItem("MILK")

        val items = repository.getAllItems().first()
        assertEquals(3, items.size)
    }

    @Test
    fun itemSearch_caseInsensitive_lowercase() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Chocolate Milk")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("milk", ignoreCase = true)
        }

        assertEquals(2, matchingItems.size)
        assertTrue(matchingItems.any { it.item.name == "Milk" })
        assertTrue(matchingItems.any { it.item.name == "Chocolate Milk" })
    }

    @Test
    fun itemSearch_caseInsensitive_uppercase() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("milk")
        repository.addItem("bread")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("MILK", ignoreCase = true)
        }

        assertEquals(1, matchingItems.size)
        assertEquals("milk", matchingItems[0].item.name)
    }

    @Test
    fun itemSearch_partialMatch() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Almond Milk")
        repository.addItem("Soy Milk")
        repository.addItem("Milk Chocolate")
        repository.addItem("Bread")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("milk", ignoreCase = true)
        }

        assertEquals(3, matchingItems.size)
        assertFalse(matchingItems.any { it.item.name == "Bread" })
    }

    @Test
    fun itemSearch_emptyQuery_matchesAll() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("", ignoreCase = true)
        }

        assertEquals(3, matchingItems.size)
    }

    @Test
    fun itemSearch_noMatch() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Milk")
        repository.addItem("Bread")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("xyz", ignoreCase = true)
        }

        assertEquals(0, matchingItems.size)
    }

    @Test
    fun itemSearch_specialCharacters() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Milk & Honey")
        repository.addItem("Bread + Butter")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("&", ignoreCase = true)
        }

        assertEquals(1, matchingItems.size)
        assertEquals("Milk & Honey", matchingItems[0].item.name)
    }

    @Test
    fun itemSearch_withSpaces() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        repository.addItem("Whole Milk")
        repository.addItem("Skim Milk")
        repository.addItem("Milk")

        val allItems = repository.getItemsForStore(storeId).first()
        val matchingItems = allItems.filter {
            it.item.name.contains("whole milk", ignoreCase = true)
        }

        assertEquals(1, matchingItems.size)
        assertEquals("Whole Milk", matchingItems[0].item.name)
    }

    @Test
    fun itemSearch_multipleStoresIndependent() = runTest {
        val store1 = repository.addStore("Store 1").getOrThrow()
        val store2 = repository.addStore("Store 2").getOrThrow()

        repository.addItem("Milk")
        repository.addItem("Bread")
        repository.addItem("Eggs")

        val items1 = repository.getItemsForStore(store1).first()
        val items2 = repository.getItemsForStore(store2).first()

        assertEquals(3, items1.size)
        assertEquals(3, items2.size)
    }

    @Test
    fun itemUpdate_preservesCase() = runTest {
        val itemId = repository.addItem("milk").getOrThrow()

        repository.updateItemName(itemId, "MILK Updated")

        val items = repository.getAllItems().first()
        assertEquals("MILK Updated", items[0].name)
    }
}
