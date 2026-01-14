package org.effekthasch.quickshopper3k.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import org.effekthasch.quickshopper3k.ui.plan.PlanFragment
import org.effekthasch.quickshopper3k.ui.shop.ShopFragment
import org.effekthasch.quickshopper3k.ui.stores.StoresFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> StoresFragment()
            1 -> PlanFragment()
            2 -> ShopFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
