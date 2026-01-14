package org.effekthasch.quickshopper3k.ui

interface FabController {
    fun onFabClick()
    fun onMenuItemSelected(itemId: Int): Boolean
    fun updateFabIcon(iconRes: Int, contentDescription: String)
    fun showFab()
    fun hideFab()
}
