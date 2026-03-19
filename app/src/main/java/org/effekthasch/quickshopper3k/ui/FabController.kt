package org.effekthasch.quickshopper3k.ui

interface FabController {
    fun onFabClick()
    fun onMenuItemSelected(itemId: Int): Boolean
    fun onMenuItemSelected(itemId: Int, menuItem: android.view.MenuItem): Boolean = onMenuItemSelected(itemId)
    fun updateFabIcon(iconRes: Int, contentDescription: String)
    fun showFab()
    fun hideFab()

    fun onBackPressed(): Boolean = false
}
