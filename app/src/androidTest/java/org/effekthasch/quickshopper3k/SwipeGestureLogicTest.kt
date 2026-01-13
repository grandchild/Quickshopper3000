package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.ui.plan.PlanViewModel
import org.effekthasch.quickshopper3k.ui.shop.ShopViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class SwipeGestureLogicTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ShoppingRepository
    private lateinit var planViewModel: PlanViewModel
    private lateinit var shopViewModel: ShopViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ShoppingRepository(database)
        planViewModel = PlanViewModel(repository)
        shopViewModel = ShopViewModel(repository)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun planMode_swipeLeft_activatedToPostponed() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeLeft(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.POSTPONED, updated.state)
    }

    @Test
    fun planMode_swipeRight_activatedToDeactivated() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeRight(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.DEACTIVATED, updated.state)
    }

    @Test
    fun planMode_swipeLeft_deactivatedToPostponed() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.DEACTIVATED)
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeLeft(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.POSTPONED, updated.state)
    }

    @Test
    fun planMode_swipeRight_deactivatedToActivated() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.DEACTIVATED)
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeRight(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.ACTIVATED, updated.state)
    }

    @Test
    fun planMode_swipeLeft_postponedToDeactivated() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.POSTPONED)
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeLeft(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.DEACTIVATED, updated.state)
    }

    @Test
    fun planMode_swipeRight_postponedToActivated() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.POSTPONED)
        val item = repository.getAllItems().first()[0]

        planViewModel.handleSwipeRight(item)

        val updated = repository.getAllItems().first()[0]
        assertEquals(ItemState.ACTIVATED, updated.state)
    }

    @Test
    fun shopMode_swipeLeft_activatedToPostponed() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeLeft(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.POSTPONED, updated.item.state)
    }

    @Test
    fun shopMode_swipeRight_activatedToDeactivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeRight(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.DEACTIVATED, updated.item.state)
    }

    @Test
    fun shopMode_swipeLeft_deactivatedToActivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.DEACTIVATED)
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeLeft(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.ACTIVATED, updated.item.state)
    }

    @Test
    fun shopMode_swipeRight_deactivatedToPostponed() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.DEACTIVATED)
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeRight(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.POSTPONED, updated.item.state)
    }

    @Test
    fun shopMode_swipeLeft_postponedToDeactivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.POSTPONED)
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeLeft(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.DEACTIVATED, updated.item.state)
    }

    @Test
    fun shopMode_swipeRight_postponedToActivated() = runTest {
        val storeId = repository.addStore("Test Store").getOrThrow()
        shopViewModel.setCurrentStore(storeId)
        val itemId = repository.addItem("Milk").getOrThrow()
        repository.setItemState(itemId, ItemState.POSTPONED)
        val storeItem = repository.getItemsForStore(storeId).first()[0]

        shopViewModel.handleSwipeRight(storeItem.item)

        val updated = repository.getItemsForStore(storeId).first()[0]
        assertEquals(ItemState.ACTIVATED, updated.item.state)
    }

    @Test
    fun swipeGestures_multipleItems_independent() = runTest {
        repository.addItem("Milk")
        repository.addItem("Bread")

        var items = repository.getAllItems().first()
        val item1 = items[0]
        val item2 = items[1]

        planViewModel.handleSwipeRight(item1)
        planViewModel.handleSwipeLeft(item2)

        kotlinx.coroutines.delay(100)

        val updated = repository.getAllItems().first()
        assertEquals(ItemState.DEACTIVATED, updated[0].state)
        assertEquals(ItemState.POSTPONED, updated[1].state)
    }

    @Test
    fun swipeGestures_sequentialSwipes_applyCorrectly() = runTest {
        repository.addItem("Milk")

        var item = repository.getAllItems().first()[0]
        assertEquals(ItemState.ACTIVATED, item.state)

        planViewModel.handleSwipeRight(item)
        item = repository.getAllItems().first()[0]
        assertEquals(ItemState.DEACTIVATED, item.state)

        planViewModel.handleSwipeRight(item)
        item = repository.getAllItems().first()[0]
        assertEquals(ItemState.ACTIVATED, item.state)

        planViewModel.handleSwipeLeft(item)
        item = repository.getAllItems().first()[0]
        assertEquals(ItemState.POSTPONED, item.state)
    }
}
