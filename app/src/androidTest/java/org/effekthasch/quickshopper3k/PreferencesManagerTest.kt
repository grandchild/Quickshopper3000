package org.effekthasch.quickshopper3k

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.effekthasch.quickshopper3k.util.PreferencesManager
import org.effekthasch.quickshopper3k.util.TextScale
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
    fun textScale_defaultsToNormal() {
        assertEquals(TextScale.NORMAL, prefsManager.textScale)
    }

    @Test
    fun textScale_storesAndRetrievesCompact() {
        prefsManager.textScale = TextScale.COMPACT

        assertEquals(TextScale.COMPACT, prefsManager.textScale)
    }

    @Test
    fun textScale_storesAndRetrievesLarge() {
        prefsManager.textScale = TextScale.LARGE

        assertEquals(TextScale.LARGE, prefsManager.textScale)
    }

    @Test
    fun textScale_persistsAcrossInstances() {
        prefsManager.textScale = TextScale.LARGE

        val newManager = PreferencesManager(context)
        assertEquals(TextScale.LARGE, newManager.textScale)
    }

    @Test
    fun textScale_multiplierValues() {
        assertEquals(0.9f, TextScale.COMPACT.multiplier, 0.01f)
        assertEquals(1.0f, TextScale.NORMAL.multiplier, 0.01f)
        assertEquals(1.2f, TextScale.LARGE.multiplier, 0.01f)
    }

    @Test
    fun showDoneItems_defaultsToFalse() {
        assertFalse(prefsManager.showDoneItems)
    }

    @Test
    fun showDoneItems_storesAndRetrievesValue() {
        prefsManager.showDoneItems = true

        assertTrue(prefsManager.showDoneItems)
    }

    @Test
    fun showDoneItems_persistsAcrossInstances() {
        prefsManager.showDoneItems = true

        val newManager = PreferencesManager(context)
        assertTrue(newManager.showDoneItems)
    }

    @Test
    fun multipleSettings_storeIndependently() {
        prefsManager.currentStoreId = 42L
        prefsManager.isLeftHanded = true
        prefsManager.textScale = TextScale.LARGE
        prefsManager.showDoneItems = true

        assertEquals(42L, prefsManager.currentStoreId)
        assertTrue(prefsManager.isLeftHanded)
        assertEquals(TextScale.LARGE, prefsManager.textScale)
        assertTrue(prefsManager.showDoneItems)
    }

    @Test
    fun multipleSettings_persistAcrossInstances() {
        prefsManager.currentStoreId = 99L
        prefsManager.isLeftHanded = true
        prefsManager.textScale = TextScale.COMPACT
        prefsManager.showDoneItems = true

        val newManager = PreferencesManager(context)
        assertEquals(99L, newManager.currentStoreId)
        assertTrue(newManager.isLeftHanded)
        assertEquals(TextScale.COMPACT, newManager.textScale)
        assertTrue(newManager.showDoneItems)
    }
}
