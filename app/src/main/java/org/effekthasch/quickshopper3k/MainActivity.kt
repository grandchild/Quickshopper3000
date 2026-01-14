package org.effekthasch.quickshopper3k

import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomappbar.BottomAppBar
import org.effekthasch.quickshopper3k.databinding.ActivityMainBinding
import org.effekthasch.quickshopper3k.ui.FabController
import org.effekthasch.quickshopper3k.ui.MainPagerAdapter
import org.effekthasch.quickshopper3k.util.PreferencesManager
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private var currentPage = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)

        setupBottomAppBar()
        setupViewPager()
        binding.viewPager.setCurrentItem(1, false)
        setupFab()
        setupOverflowButton()
        setupStoreSelectorButton()
        applyHandednessSettings()
    }

    private fun setupBottomAppBar() {
        updateBottomAppBarForPage(1)
        setupBottomBarGestures()
    }

    private fun setupOverflowButton() {
        binding.overflowMenuButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.bottom_app_bar_menu, popup.menu)

            popup.menu.findItem(R.id.action_toggle_done_items)?.isVisible = currentPage == 2
            popup.menu.findItem(R.id.action_store_selector)?.isVisible = false
            popup.menu.findItem(R.id.action_select_store)?.isVisible = false
            popup.menu.findItem(R.id.action_edit_store)?.isVisible = currentPage == 0
            popup.menu.findItem(R.id.action_delete_store)?.isVisible = currentPage == 0
            popup.menu.findItem(R.id.action_import_items)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_activate_all)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_deactivate_all)?.isVisible = currentPage == 1

            popup.setOnMenuItemClickListener { item ->
                onOptionsItemSelected(item)
            }

            popup.show()
        }
    }

    private fun setupStoreSelectorButton() {
        binding.storeSelectorButton.setOnClickListener {
            val fragment = getCurrentFragment()
            if (fragment is org.effekthasch.quickshopper3k.ui.shop.ShopFragment) {
                fragment.showStoreSelectionDropdown(null)
            } else if (fragment is org.effekthasch.quickshopper3k.ui.stores.StoresFragment) {
                fragment.showStoreSelectionDialog()
            }
        }
    }

    fun updateStoreSelectorButton() {
        when (currentPage) {
            0 -> {
                binding.storeSelectorButton.visibility = android.view.View.VISIBLE
                val storesFragment = getCurrentFragment() as? org.effekthasch.quickshopper3k.ui.stores.StoresFragment
                binding.storeSelectorButton.text = storesFragment?.getCurrentStoreName() ?: "◉"
            }

            2 -> {
                binding.storeSelectorButton.visibility = android.view.View.VISIBLE
                val shopFragment = getCurrentFragment() as? org.effekthasch.quickshopper3k.ui.shop.ShopFragment
                binding.storeSelectorButton.text = shopFragment?.getStoreMenuTitle() ?: "◉"
            }

            else -> {
                binding.storeSelectorButton.visibility = android.view.View.GONE
            }
        }
    }

    private fun setupBottomBarGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY) && abs(diffX) > 100 && abs(velocityX) > 100) {
                    if (diffX > 0 && currentPage > 0) {
                        binding.viewPager.currentItem = currentPage - 1
                        return true
                    } else if (diffX < 0 && currentPage < 2) {
                        binding.viewPager.currentItem = currentPage + 1
                        return true
                    }
                }
                return false
            }
        })

        binding.bottomAppBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPage = position
                updateBottomAppBarForPage(position)
                updateFabForPage(position)
                updateStoreSelectorButton()
                invalidateOptionsMenu()
            }
        })
    }

    private fun setupFab() {
        updateFabForPage(0)
        binding.fab.setOnClickListener {
            getCurrentFragment()?.onFabClick()
        }
    }

    private fun getCurrentFragment(): FabController? {
        val adapter = binding.viewPager.adapter as? MainPagerAdapter ?: return null
        val fragment = supportFragmentManager.findFragmentByTag("f${currentPage}") as? Fragment
        return fragment as? FabController
    }

    private fun applyHandednessSettings() {
        val isLeftHanded = prefsManager.isLeftHanded
        val layoutParams = binding.fab.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        layoutParams.anchorGravity = if (isLeftHanded) {
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        } else {
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        binding.fab.layoutParams = layoutParams
    }

    private fun updateBottomAppBarForPage(position: Int) {
        // Title removed for space
    }

    private fun updateFabForPage(position: Int) {
        when (position) {
            0 -> {
                binding.fab.setImageResource(android.R.drawable.ic_input_add)
                binding.fab.contentDescription = getString(R.string.add_store)
            }

            1 -> {
                binding.fab.setImageResource(android.R.drawable.ic_input_add)
                binding.fab.contentDescription = getString(R.string.add_item)
            }

            2 -> {
                binding.fab.setImageResource(android.R.drawable.ic_menu_search)
                binding.fab.contentDescription = getString(R.string.search)
            }
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateStoreSelectorButton()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val fragment = getCurrentFragment()
        if (fragment != null && fragment.onMenuItemSelected(item.itemId, item)) {
            return true
        }

        return when (item.itemId) {
            R.id.action_settings -> {
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    fun hideBottomBar() {
        binding.bottomAppBar.performHide()
        binding.fab.hide()
    }

    fun showBottomBar() {
        binding.bottomAppBar.performShow()
        binding.fab.show()
    }
}
