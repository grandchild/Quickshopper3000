package org.effekthasch.quickshopper3k.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.domain.statistics.PurchaseForecast
import kotlin.math.sqrt

class PurchaseHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val historyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var activations: List<Long> = emptyList()
    private var forecast: PurchaseForecast? = null
    private var showPrediction = false
    private var now: Long = System.currentTimeMillis()

    fun bind(
        activations: List<Long>,
        forecast: PurchaseForecast?,
        showPrediction: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        this.activations = activations
        this.forecast = forecast
        this.showPrediction = showPrediction
        this.now = now
        visibility = if (activations.isEmpty() && forecast == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val predictionSlot = height.toFloat() + 6f
        val historyRight = width.toFloat() - predictionSlot
        drawHistory(canvas, historyRight)
        drawPrediction(canvas)
    }

    private fun drawHistory(canvas: Canvas, historyRight: Float) {
        if (historyRight <= 0f) return

        val start = now - DISPLAY_DAYS * MILLIS_PER_DAY
        val displayDuration = DISPLAY_DAYS.toLong() * MILLIS_PER_DAY
        val fadeStart = historyRight * 0.5f
        val maximumDiameter = height * 0.5f
        historyPaint.color = themeColor(android.R.attr.textColorSecondary, 0xFF777777.toInt())

        repeat(HISTORY_BUCKETS) { bucket ->
            val bucketStart = start + displayDuration * bucket / HISTORY_BUCKETS
            val bucketEnd = start + displayDuration * (bucket + 1) / HISTORY_BUCKETS
            val occurrences = activations.count { it >= bucketStart && it < bucketEnd }
            if (occurrences == 0) return@repeat

            val x = historyRight * (bucket + 0.5f) / HISTORY_BUCKETS
            if (x <= fadeStart) return@repeat

            val fade = ((x - fadeStart) / (historyRight - fadeStart)).coerceIn(0f, 1f)
            val diameter = (maximumDiameter * (0.45f + 0.12f * (occurrences - 1)))
                .coerceAtMost(maximumDiameter)
            historyPaint.alpha = (220f * fade).toInt().coerceIn(0, 220)
            canvas.drawCircle(x, height * 0.75f, diameter * 0.5f, historyPaint)
        }
    }

    private fun drawPrediction(canvas: Canvas) {
        if (!showPrediction) return
        val currentForecast = forecast ?: return
        val probability = currentForecast.probability.coerceIn(0.0, 1.0)
        val confidence = currentForecast.confidence.coerceIn(0.0, 1.0)
        val diameter = height * (0.1f + 0.9f * sqrt(probability).toFloat())
        val radius = diameter * 0.5f
        predictionPaint.color = ContextCompat.getColor(context, R.color.purchase_prediction)
        predictionPaint.alpha = (50 + 205 * probability * (0.55 + 0.45 * confidence))
            .toInt()
            .coerceIn(35, 255)
        val centerX = width.toFloat() - height * 0.5f - 3f
        canvas.drawCircle(centerX, height * 0.5f, radius, predictionPaint)
    }

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
        const val DISPLAY_DAYS = 1095
        const val HISTORY_BUCKETS = 52
    }
}
