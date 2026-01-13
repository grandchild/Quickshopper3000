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
import org.junit.runners.Parameterized
import org.junit.Assert.*

@RunWith(Parameterized::class)
class StateTransitionParameterizedTest(
    private val fromState: ItemState,
    private val toState: ItemState,
    private val testName: String
) {
    private lateinit var database: AppDatabase
    private lateinit var repository: ShoppingRepository

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{2}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                arrayOf(ItemState.ACTIVATED, ItemState.DEACTIVATED, "activated → deactivated"),
                arrayOf(ItemState.ACTIVATED, ItemState.POSTPONED, "activated → postponed"),
                arrayOf(ItemState.DEACTIVATED, ItemState.ACTIVATED, "deactivated → activated"),
                arrayOf(ItemState.DEACTIVATED, ItemState.POSTPONED, "deactivated → postponed"),
                arrayOf(ItemState.POSTPONED, ItemState.ACTIVATED, "postponed → activated"),
                arrayOf(ItemState.POSTPONED, ItemState.DEACTIVATED, "postponed → deactivated")
            )
        }
    }

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
    fun stateTransition_planMode() = runTest {
        val itemId = repository.addItem("Milk").getOrThrow()
        if (fromState != ItemState.ACTIVATED) {
            repository.setItemState(itemId, fromState)
        }

        repository.setItemState(itemId, toState)

        val items = repository.getAllItems().first()
        assertEquals(toState, items[0].state)
    }

    @Test
    fun stateTransition_shopMode() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()
        if (fromState != ItemState.ACTIVATED) {
            repository.setItemState(itemId, fromState)
        }

        repository.setItemState(itemId, toState)

        val items = repository.getItemsForStore(storeId).first()
        assertEquals(toState, items[0].item.state)
    }
}

@RunWith(AndroidJUnit4::class)
class StateTransitionAdditionalTest {
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
    fun stateTransitions_persistAcrossReads() = runTest {
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
    fun multipleItems_independentStates() = runTest {
        val item1 = repository.addItem("Milk").getOrThrow()
        val item2 = repository.addItem("Bread").getOrThrow()
        val item3 = repository.addItem("Eggs").getOrThrow()

        repository.setItemState(item1, ItemState.ACTIVATED)
        repository.setItemState(item2, ItemState.DEACTIVATED)
        repository.setItemState(item3, ItemState.POSTPONED)

        val items = repository.getAllItems().first()
        assertEquals(ItemState.ACTIVATED, items[0].state)
        assertEquals(ItemState.DEACTIVATED, items[1].state)
        assertEquals(ItemState.POSTPONED, items[2].state)
    }

    @Test
    fun stateChanges_reflectInStoreView() = runTest {
        val storeId = repository.addStore("Aldi").getOrThrow()
        val itemId = repository.addItem("Milk").getOrThrow()

        repository.setItemState(itemId, ItemState.POSTPONED)
        var items = repository.getItemsForStore(storeId).first()
        assertEquals(ItemState.POSTPONED, items[0].item.state)

        repository.setItemState(itemId, ItemState.DEACTIVATED)
        items = repository.getItemsForStore(storeId).first()
        assertEquals(ItemState.DEACTIVATED, items[0].item.state)
    }

    @Test
    fun newItems_defaultToActivated() = runTest {
        repository.addItem("Milk")

        val items = repository.getAllItems().first()
        assertEquals(ItemState.ACTIVATED, items[0].state)
    }
}
