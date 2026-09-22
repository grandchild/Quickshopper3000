package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.effekthasch.quickshopper3k.util.ItemRowHeight
import org.effekthasch.quickshopper3k.util.PreferencesManager

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class PreferencesManagerTest {
    private lateinit var context: Context
    private lateinit var prefsManager: PreferencesManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefsManager = PreferencesManager(context)
        clearPreferences()
    }

    @After
    fun teardown() {
        clearPreferences()
    }

    private fun clearPreferences() {
        context.getSharedPreferences("quickshopper3k_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun currentStoreId_defaultsToNull() {
        assertNull(prefsManager.currentStoreId)
    }

    @Test
    fun currentStoreId_storesAndRetrievesValue() {
        prefsManager.currentStoreId = 42L

        assertEquals(42L, prefsManager.currentStoreId)
    }

    @Test
    fun currentStoreId_canBeSetToNull() {
        prefsManager.currentStoreId = 42L
        prefsManager.currentStoreId = null

        assertNull(prefsManager.currentStoreId)
    }

    @Test
    fun currentStoreId_persistsAcrossInstances() {
        prefsManager.currentStoreId = 123L

        val newManager = PreferencesManager(context)
        assertEquals(123L, newManager.currentStoreId)
    }

    @Test
    fun itemRowHeightIndex_defaultsToMiddle() {
        assertEquals(ItemRowHeight.defaultIndex, prefsManager.itemRowHeightIndex)
    }

    @Test
    fun itemRowHeightIndex_persistsAndStaysWithinRange() {
        prefsManager.itemRowHeightIndex = ItemRowHeight.lastIndex

        assertEquals(ItemRowHeight.lastIndex, PreferencesManager(context).itemRowHeightIndex)

        prefsManager.itemRowHeightIndex = Int.MAX_VALUE
        assertEquals(ItemRowHeight.lastIndex, prefsManager.itemRowHeightIndex)
    }

    @Test
    fun itemRowHeightIndex_migratesExistingFiveStopValue() {
        clearPreferences()
        context.getSharedPreferences("quickshopper3k_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("item_row_height_index", ItemRowHeight.lastIndex)
            .commit()

        assertEquals(ItemRowHeight.lastIndex, PreferencesManager(context).itemRowHeightIndex)
    }

    @Test
    fun isLeftHanded_defaultsToFalse() {
        assertFalse(prefsManager.isLeftHanded)
    }

    @Test
    fun isLeftHanded_storesAndRetrievesValue() {
        prefsManager.isLeftHanded = true

        assertTrue(prefsManager.isLeftHanded)
    }

    @Test
    fun isLeftHanded_persistsAcrossInstances() {
        prefsManager.isLeftHanded = true

        val newManager = PreferencesManager(context)
        assertTrue(newManager.isLeftHanded)
    }


    @Test
    fun multipleSettings_storeIndependently() {
        prefsManager.currentStoreId = 42L
        prefsManager.isLeftHanded = true

        assertEquals(42L, prefsManager.currentStoreId)
        assertTrue(prefsManager.isLeftHanded)
    }

    @Test
    fun multipleSettings_persistAcrossInstances() {
        prefsManager.currentStoreId = 99L
        prefsManager.isLeftHanded = true

        val newManager = PreferencesManager(context)
        assertEquals(99L, newManager.currentStoreId)
        assertTrue(newManager.isLeftHanded)
    }
}
