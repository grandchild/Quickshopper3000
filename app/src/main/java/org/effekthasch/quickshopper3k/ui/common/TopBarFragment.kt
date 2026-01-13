package org.effekthasch.quickshopper3k.ui.common

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import org.effekthasch.quickshopper3k.R

abstract class TopBarFragment : Fragment() {

    private var topBar: MaterialToolbar? = null
    private var modeTitle: TextView? = null
    private var gestureDetector: GestureDetector? = null

    @get:StringRes
    abstract val modeTitleRes: Int

    @get:ColorRes
    abstract val modeBgColorRes: Int

    @get:MenuRes
    abstract val menuRes: Int

    abstract fun onModeSwipeLeft()
    abstract fun onModeSwipeRight()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTopBar(view)
    }

    private fun setupTopBar(view: View) {
        topBar = view.findViewById(R.id.topBar)
        modeTitle = view.findViewById(R.id.modeTitle)

        topBar?.let { toolbar ->
            modeTitle?.setText(modeTitleRes)

            val bgColor = ContextCompat.getColor(requireContext(), modeBgColorRes)
            toolbar.setBackgroundColor(bgColor)

            toolbar.inflateMenu(menuRes)
            toolbar.setOnMenuItemClickListener { menuItem ->
                onOptionsItemSelected(menuItem)
            }

            gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false

                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y

                    if (Math.abs(diffX) > Math.abs(diffY)) {
                        if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                            if (diffX > 0) {
                                onModeSwipeRight()
                            } else {
                                onModeSwipeLeft()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            toolbar.setOnTouchListener { _, event ->
                gestureDetector?.onTouchEvent(event) ?: false
            }
        }
    }

    protected fun animateTopBarColorTransition(@ColorRes toColorRes: Int) {
        topBar?.let { toolbar ->
            val fromColor = (toolbar.background as? android.graphics.drawable.ColorDrawable)?.color
                ?: ContextCompat.getColor(requireContext(), modeBgColorRes)
            val toColor = ContextCompat.getColor(requireContext(), toColorRes)

            ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
                duration = 300
                addUpdateListener { animator ->
                    toolbar.setBackgroundColor(animator.animatedValue as Int)
                }
                start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        topBar = null
        modeTitle = null
        gestureDetector = null
    }
}
