package org.effekthasch.quickshopper3k

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope

import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomappbar.BottomAppBar
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.sync.SyncManager
import org.effekthasch.quickshopper3k.databinding.ActivityMainBinding
import org.effekthasch.quickshopper3k.ui.FabController
import org.effekthasch.quickshopper3k.ui.MainPagerAdapter
import org.effekthasch.quickshopper3k.util.DoneVisibilityMode
import org.effekthasch.quickshopper3k.util.PreferencesManager
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var syncManager: SyncManager
    private var currentPage = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)



        prefsManager = PreferencesManager(this)
        syncManager = SyncManager(
            database = AppDatabase.getDatabase(this),
            serverUrlProvider = { prefsManager.syncServerUri },
            deviceIdProvider = { prefsManager.deviceId },
            listIdProvider = { prefsManager.listId }
        )

        setupBottomAppBar()
        setupViewPager()
        binding.viewPager.setCurrentItem(1, false)
        setupFab()
        setupOverflowButton()
        setupStoreSelectorButton()
        setupDoneVisibilityButton()
        applyHandednessSettings()
        updateLockscreenFlags(currentPage)
    }

    override fun onResume() {
        super.onResume()
        syncManager.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        syncManager.stop()
    }

    private fun setupBottomAppBar() {
        updateBottomAppBarForPage(1)
        setupBottomBarGestures()
        setupSelectionCounter()
    }

    private fun setupSelectionCounter() {
        binding.selectionCounterText.setOnClickListener {
            val currentFragment = supportFragmentManager.findFragmentByTag("f$currentPage")
            if (currentFragment is org.effekthasch.quickshopper3k.ui.stores.StoresFragment) {
                currentFragment.clearSelection()
            }
        }
    }

    fun updateSelectionCounter(count: Int) {
        if (count > 0) {
            binding.selectionCounterText.text = count.toString()
            binding.selectionCounterText.visibility = android.view.View.VISIBLE
        } else {
            binding.selectionCounterText.visibility = android.view.View.GONE
        }
    }

    private fun setupOverflowButton() {
        binding.overflowMenuButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.bottom_app_bar_menu, popup.menu)

            runCatching {
                val helperField = popup.javaClass.getDeclaredField("mPopup")
                helperField.isAccessible = true
                val menuHelper = helperField.get(popup)
                menuHelper?.javaClass?.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                    ?.invoke(menuHelper, true)
            }

            popup.menu.findItem(R.id.action_store_selector)?.isVisible = false
            popup.menu.findItem(R.id.action_select_store)?.isVisible = false
            popup.menu.findItem(R.id.action_edit_store)?.isVisible = currentPage == 0
            popup.menu.findItem(R.id.action_delete_store)?.isVisible = currentPage == 0
            popup.menu.findItem(R.id.action_import_items)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_import_backup)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_export_backup)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_activate_all)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_deactivate_all)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_flush_recently_done)?.isVisible = currentPage == 1
            popup.menu.findItem(R.id.action_share_active_items)?.isVisible = currentPage == 1

            popup.setOnMenuItemClickListener { item ->
                onOptionsItemSelected(item)
            }

            popup.show()
        }
    }

    private fun setupDoneVisibilityButton() {
        binding.doneVisibilityButton.setOnClickListener {
            val mainFragment = getCurrentFragment() as? org.effekthasch.quickshopper3k.ui.main.MainFragment
            mainFragment?.cycleDoneVisibilityMode()
            updateDoneVisibilityButton()
        }
    }

    fun updateDoneVisibilityButton() {
        if (currentPage == 1) {
            binding.doneVisibilityButton.visibility = android.view.View.VISIBLE
            val mainFragment = getCurrentFragment() as? org.effekthasch.quickshopper3k.ui.main.MainFragment
            val mode = mainFragment?.getDoneVisibilityMode() ?: DoneVisibilityMode.RECENT
            binding.doneVisibilityButton.text = mode.label()
        } else {
            binding.doneVisibilityButton.visibility = android.view.View.GONE
        }
    }

    private fun setupStoreSelectorButton() {
        binding.storeSelectorButton.setOnClickListener {
            val fragment = getCurrentFragment()
            if (fragment is org.effekthasch.quickshopper3k.ui.main.MainFragment) {
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
                binding.storeSelectorButton.text = storesFragment?.getCurrentStoreName()
                    ?: getString(R.string.store_indicator)
            }

            1 -> {
                binding.storeSelectorButton.visibility = android.view.View.VISIBLE
                val mainFragment = getCurrentFragment() as? org.effekthasch.quickshopper3k.ui.main.MainFragment
                binding.storeSelectorButton.text = mainFragment?.getStoreMenuTitle()
                    ?: getString(R.string.store_indicator)
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
                    } else if (diffX < 0 && currentPage < 1) {
                        binding.viewPager.currentItem = currentPage + 1
                        return true
                    }
                }
                return false
            }
        })

        binding.bottomAppBar.setOnTouchListener { view, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (handled && event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
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
                updateDoneVisibilityButton()
                updateLockscreenFlags(position)
                if (position != 0) {
                    updateSelectionCounter(0)
                }
                invalidateOptionsMenu()
            }
        })
    }

    private fun updateLockscreenFlags(position: Int) {
        val enable = position == 1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enable)
            setTurnScreenOn(enable)
        } else {
            // Keep legacy flags for pre-O_MR1 in a single APK.
            // We only drop them when minSdk is raised.
            @Suppress("DEPRECATION")
            if (enable) {
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    private fun setupFab() {
        updateFabForPage(0)
        binding.fab.setOnClickListener {
            getCurrentFragment()?.onFabClick()
        }
    }

    private fun getCurrentFragment(): FabController? {
        binding.viewPager.adapter ?: return null
        val fragment = supportFragmentManager.findFragmentByTag("f${currentPage}")
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

    @Suppress("UNUSED_PARAMETER")
    private fun updateBottomAppBarForPage(position: Int) {
    }

    private fun updateFabForPage(position: Int) {
        when (position) {
            0 -> {
                binding.fab.setImageResource(android.R.drawable.ic_input_add)
                binding.fab.contentDescription = getString(R.string.add_store)
            }

            1 -> {
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
                startActivity(Intent(this, org.effekthasch.quickshopper3k.ui.SettingsActivity::class.java))
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
