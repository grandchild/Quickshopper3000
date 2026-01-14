package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.util.PreferencesManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class StoresManagementTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ShoppingRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getDatabase(context)
        repository = ShoppingRepository(database)
        preferencesManager = PreferencesManager(context)

        runBlocking {
            database.clearAllTables()
        }
        preferencesManager.currentStoreId = null
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addStore_createsStoreSuccessfully() = runBlocking {
        val result = repository.addStore("Supermarket", false)

        assertTrue(result.isSuccess)
        val storeId = result.getOrNull()
        assertNotNull(storeId)

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("Supermarket", stores[0].name)
        assertFalse(stores[0].specialtyStore)
    }

    @Test
    fun addStore_withSpecialtyFlag_createsSpecialtyStore() = runBlocking {
        val result = repository.addStore("Asian Market", true)

        assertTrue(result.isSuccess)

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("Asian Market", stores[0].name)
        assertTrue(stores[0].specialtyStore)
    }

    @Test
    fun addMultipleStores_allStoresCreated() = runBlocking {
        repository.addStore("Store A", false)
        repository.addStore("Store B", true)
        repository.addStore("Store C", false)

        val stores = repository.getAllStores().first()
        assertEquals(3, stores.size)
        assertEquals("Store A", stores[0].name)
        assertEquals("Store B", stores[1].name)
        assertEquals("Store C", stores[2].name)
        assertFalse(stores[0].specialtyStore)
        assertTrue(stores[1].specialtyStore)
        assertFalse(stores[2].specialtyStore)
    }

    @Test
    fun updateStore_changesNameAndSpecialtyFlag() = runBlocking {
        val result = repository.addStore("Old Name", false)
        val storeId = result.getOrThrow()

        repository.updateStore(storeId, "New Name", true)

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("New Name", stores[0].name)
        assertTrue(stores[0].specialtyStore)
    }

    @Test
    fun updateStore_nonExistentStore_fails() = runBlocking {
        val result = repository.updateStore(999L, "Test", false)

        assertTrue(result.isFailure)
    }

    @Test
    fun deleteStore_removesStore() = runBlocking {
        val result1 = repository.addStore("Store 1", false)
        val result2 = repository.addStore("Store 2", false)
        val storeId1 = result1.getOrThrow()

        repository.deleteStore(storeId1)

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("Store 2", stores[0].name)
    }

    @Test
    fun deleteStore_clearsCurrentStoreIfDeleted() = runBlocking {
        val result = repository.addStore("Store", false)
        val storeId = result.getOrThrow()

        preferencesManager.currentStoreId = storeId
        assertEquals(storeId, preferencesManager.currentStoreId)

        repository.deleteStore(storeId)

        preferencesManager.currentStoreId = null
        assertNull(preferencesManager.currentStoreId)
    }

    @Test
    fun addStore_autoSelectsNewStore() = runBlocking {
        assertNull(preferencesManager.currentStoreId)

        val result = repository.addStore("First Store", false)
        val storeId = result.getOrThrow()

        preferencesManager.currentStoreId = storeId
        assertEquals(storeId, preferencesManager.currentStoreId)
    }

    @Test
    fun getStoreById_returnsCorrectStore() = runBlocking {
        val result = repository.addStore("Test Store", true)
        val storeId = result.getOrThrow()

        val store = repository.getStoreById(storeId)

        assertNotNull(store)
        assertEquals("Test Store", store?.name)
        assertTrue(store?.specialtyStore ?: false)
    }

    @Test
    fun getStoreById_nonExistent_returnsNull() = runBlocking {
        val store = repository.getStoreById(999L)

        assertNull(store)
    }

    @Test
    fun storesAreSortedByName() = runBlocking {
        repository.addStore("Zebra Store", false)
        repository.addStore("Alpha Store", false)
        repository.addStore("Beta Store", false)

        val stores = repository.getAllStores().first()

        assertEquals(3, stores.size)
        assertEquals("Alpha Store", stores[0].name)
        assertEquals("Beta Store", stores[1].name)
        assertEquals("Zebra Store", stores[2].name)
    }

    @Test
    fun addStore_withEmptyName_succeeds() = runBlocking {
        val result = repository.addStore("", false)

        assertTrue(result.isSuccess)

        val stores = repository.getAllStores().first()
        assertEquals(1, stores.size)
        assertEquals("", stores[0].name)
    }

    @Test
    fun addItemToNewStore_itemsGetDefaultOrder() = runBlocking {
        val storeResult = repository.addStore("New Store", false)
        val storeId = storeResult.getOrThrow()

        repository.addItem("Item 1")
        repository.addItem("Item 2")
        repository.addItem("Item 3")

        val storeItems = repository.getItemsForStore(storeId).first()

        assertEquals(3, storeItems.size)
        storeItems.forEach { storeItem ->
            assertNull(storeItem.sortOrder)
            assertTrue(storeItem.isAvailable)
        }
    }

    @Test
    fun deleteStore_removesStoreOrderAndUnavailability() = runBlocking {
        val storeResult = repository.addStore("Test Store", false)
        val storeId = storeResult.getOrThrow()

        val itemResult = repository.addItem("Test Item")
        val itemId = itemResult.getOrThrow()

        repository.setItemAvailability(storeId, itemId, false)
        repository.updateStoreItemOrders(storeId, listOf(itemId to 1))

        repository.deleteStore(storeId)

        val stores = repository.getAllStores().first()
        assertEquals(0, stores.size)
    }
}
