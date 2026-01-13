package org.effekthasch.quickshopper3k.ui.common

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import org.effekthasch.quickshopper3k.ui.common.BaseDragAdapter

abstract class BaseListFragment<T> : Fragment() {
    protected lateinit var adapter: BaseDragAdapter<T, out RecyclerView.ViewHolder>
    protected lateinit var recyclerView: RecyclerView
    protected lateinit var bottomBarContainer: ViewGroup
    protected lateinit var bottomBarInput: EditText
    protected var bottomBarToggle: View? = null

    protected var isSwiping: Boolean = false
    protected var isDragging: Boolean = false

    private lateinit var itemTouchHelper: ItemTouchHelper

    abstract fun provideAdapter(): BaseDragAdapter<T, out RecyclerView.ViewHolder>
    abstract fun onSwipeLeft(item: T)
    abstract fun onSwipeRight(item: T)
    abstract fun persistOrder(items: List<T>)
    abstract fun onBottomBarTextChanged(text: String)
    abstract fun bottomBarImeAction(): Int
    abstract fun getSwipeRightTargetState(item: T): String
    abstract fun getSwipeLeftTargetState(item: T): String

    protected fun setBottomBarToggleViewCompat(view: View) {
        bottomBarToggle = view
    }

    protected fun setupRecyclerView(rv: RecyclerView) {
        recyclerView = rv
        adapter = provideAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val swipe = SwipeCallback()
        val drag = DragCallback()
        val combined = CombinedCallback(swipe, drag)
        itemTouchHelper = ItemTouchHelper(combined)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    protected fun enableDragOn(holder: RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(holder)
    }

    protected fun setupBottomBar(container: ViewGroup, input: EditText) {
        bottomBarContainer = container
        bottomBarInput = input

        bottomBarInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == bottomBarImeAction()) {
                bottomBarInput.clearFocus()
                true
            } else {
                false
            }
        }

        bottomBarInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideBottomBar()
            }
        }

        recyclerView.setOnTouchListener { _, _ ->
            if (bottomBarContainer.visibility == View.VISIBLE) {
                bottomBarInput.clearFocus()
                true
            } else {
                false
            }
        }

        bottomBarInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onBottomBarTextChanged(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    protected fun showBottomBar(presetText: String? = null) {
        bottomBarContainer.visibility = View.VISIBLE
        bottomBarToggle?.visibility = View.GONE
        bottomBarInput.setText(presetText ?: "")
        bottomBarInput.requestFocus()
        val imm = ContextCompat.getSystemService(
            requireContext(),
            android.view.inputmethod.InputMethodManager::class.java
        )
        imm?.showSoftInput(bottomBarInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    protected fun hideBottomBar() {
        bottomBarContainer.visibility = View.GONE
        bottomBarToggle?.visibility = View.VISIBLE
        val imm = ContextCompat.getSystemService(
            requireContext(),
            android.view.inputmethod.InputMethodManager::class.java
        )
        imm?.hideSoftInputFromWindow(bottomBarInput.windowToken, 0)
    }

    protected fun applyItems(items: List<T>) {
        if (!isSwiping && !isDragging) {
            adapter.setItems(items)
        }
    }

    inner class SwipeCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private var pendingSwipeAction: Pair<T, Int>? = null

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                isSwiping = true
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                val item = adapter.getItems()[position]
                pendingSwipeAction = Pair(item, direction)
                adapter.notifyItemChanged(position)
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            isSwiping = false

            pendingSwipeAction?.let { (item, direction) ->
                when (direction) {
                    ItemTouchHelper.LEFT -> onSwipeLeft(item)
                    ItemTouchHelper.RIGHT -> onSwipeRight(item)
                }
                pendingSwipeAction = null
                adapter.setItems(adapter.getItems())
            }
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                drawSwipeBackground(c, viewHolder, dX, dY)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        private fun drawSwipeBackground(
            c: android.graphics.Canvas,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float
        ) {
            val itemView = viewHolder.itemView
            val position = viewHolder.bindingAdapterPosition

            if (position == RecyclerView.NO_POSITION) return

            val item = adapter.getItems()[position]
            val paint = android.graphics.Paint()
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 48f
                textAlign = android.graphics.Paint.Align.CENTER
            }

            if (dX > 0) {
                val targetState = getSwipeRightTargetState(item)
                val color = getColorForState(targetState)

                paint.color = color
                c.drawRect(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left + dX,
                    itemView.bottom.toFloat(),
                    paint
                )

                drawStateLabel(c, itemView, dX, targetState, textPaint, isLeftSide = true)
            } else if (dX < 0) {
                val targetState = getSwipeLeftTargetState(item)
                val color = getColorForState(targetState)

                paint.color = color
                c.drawRect(
                    itemView.right + dX,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                    paint
                )

                drawStateLabel(c, itemView, dX, targetState, textPaint, isLeftSide = false)
            }
        }

        private fun getColorForState(targetState: String): Int {
            return when (targetState) {
                "Done" -> android.graphics.Color.parseColor("#4CAF50")
                "N/A" -> android.graphics.Color.parseColor("#FF9800")
                else -> android.graphics.Color.parseColor("#2196F3")
            }
        }

        private fun drawStateLabel(
            c: android.graphics.Canvas,
            itemView: android.view.View,
            dX: Float,
            targetState: String,
            textPaint: android.graphics.Paint,
            isLeftSide: Boolean
        ) {
            if (targetState.isEmpty()) return

            val iconPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                isAntiAlias = true
            }

            val iconSize = 50f
            val spacing = 10f
            val centerX = if (isLeftSide) {
                itemView.left + dX / 2
            } else {
                itemView.right + dX / 2
            }
            val centerY = itemView.top + (itemView.height / 2f)

            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(targetState, 0, targetState.length, textBounds)
            val totalWidth = iconSize + spacing + textBounds.width()

            val iconX = centerX - totalWidth / 2 + iconSize / 2
            val textX = centerX - totalWidth / 2 + iconSize + spacing + textBounds.width() / 2

            when (targetState) {
                "Done" -> drawCheckmark(c, iconX, centerY, iconSize, iconPaint)
                "N/A" -> drawX(c, iconX, centerY, iconSize, iconPaint)
            }

            c.drawText(targetState, textX, centerY + textBounds.height() / 2, textPaint)
        }

        private fun drawCheckmark(
            canvas: android.graphics.Canvas,
            centerX: Float,
            centerY: Float,
            size: Float,
            paint: android.graphics.Paint
        ) {
            val path = android.graphics.Path()
            path.moveTo(centerX - size * 0.3f, centerY)
            path.lineTo(centerX - size * 0.1f, centerY + size * 0.3f)
            path.lineTo(centerX + size * 0.4f, centerY - size * 0.3f)

            val strokePaint = android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.15f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            canvas.drawPath(path, strokePaint)
        }

        private fun drawX(
            canvas: android.graphics.Canvas,
            centerX: Float,
            centerY: Float,
            size: Float,
            paint: android.graphics.Paint
        ) {
            val strokePaint = android.graphics.Paint(paint).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = size * 0.15f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            canvas.drawLine(
                centerX - size * 0.3f, centerY - size * 0.3f,
                centerX + size * 0.3f, centerY + size * 0.3f,
                strokePaint
            )
            canvas.drawLine(
                centerX + size * 0.3f, centerY - size * 0.3f,
                centerX - size * 0.3f, centerY + size * 0.3f,
                strokePaint
            )
        }
    }

    inner class DragCallback : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPosition = viewHolder.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition

            if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                adapter.moveItem(fromPosition, toPosition)
            }
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                isDragging = true
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val finalList = adapter.getItems()
            persistOrder(finalList)
            isDragging = false
        }

        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = true
    }

    inner class CombinedCallback(
        private val swipeCallback: SwipeCallback,
        private val dragCallback: DragCallback
    ) : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            val swipeFlags = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(dragFlags, swipeFlags)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = dragCallback.onMove(recyclerView, viewHolder, target)

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            swipeCallback.onSwiped(viewHolder, direction)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_SWIPE -> swipeCallback.onSelectedChanged(viewHolder, actionState)
                ItemTouchHelper.ACTION_STATE_DRAG -> dragCallback.onSelectedChanged(viewHolder, actionState)
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            swipeCallback.clearView(recyclerView, viewHolder)
            dragCallback.clearView(recyclerView, viewHolder)
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                swipeCallback.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = true
    }
}
