package org.effekthasch.quickshopper3k.domain.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseStatisticsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun emptyHistory_hasNoForecast() {
        assertTrue(PurchaseStatistics.forecast(emptyList(), now).isEmpty())
    }

    @Test
    fun firstActivationIsAnAnchor_notAPurchaseEvent() {
        val forecasts = PurchaseStatistics.forecast(
            listOf(PurchaseHistory(1L, now - 30 * day, listOf(now - 30 * day))),
            now
        )

        val forecast = forecasts.getValue(1L)
        assertEquals(0.0, forecast.confidence, 0.0)
        assertTrue(forecast.probability.isFinite())
    }

    @Test
    fun regularHistory_hasHigherProbabilityNearUsualInterval() {
        val history = PurchaseHistory(
            itemId = 1L,
            observedFrom = now - 60 * day,
            activations = listOf(
                now - 60 * day,
                now - 46 * day,
                now - 32 * day,
                now - 18 * day
            )
        )
        val near = PurchaseStatistics.forecast(listOf(history), now).getValue(1L)
        val laterHistory = history.copy(
            activations = history.activations.dropLast(1) + (now - 2 * day)
        )
        val later = PurchaseStatistics.forecast(listOf(laterHistory), now).getValue(1L)

        assertTrue(near.probability > later.probability)
        assertTrue(near.confidence > 0.0)
    }

    @Test
    fun historyBeforeItemStart_isIgnored() {
        val history = PurchaseHistory(
            itemId = 1L,
            observedFrom = now - 20 * day,
            activations = listOf(
                now - 40 * day,
                now - 20 * day,
                now - 6 * day
            )
        )
        val forecast = PurchaseStatistics.forecast(listOf(history), now).getValue(1L)

        assertTrue(forecast.probability.isFinite())
        assertTrue(forecast.confidence > 0.0)
    }

    @Test
    fun recentBurst_increasesProbability() {
        val stable = PurchaseHistory(
            itemId = 1L,
            observedFrom = now - 365 * day,
            activations = listOf(now - 365 * day, now - 180 * day, now - 90 * day)
        )
        val burst = stable.copy(
            activations = stable.activations + listOf(now - 10 * day, now - 3 * day)
        )

        val stableForecast = PurchaseStatistics.forecast(listOf(stable), now).getValue(1L)
        val burstForecast = PurchaseStatistics.forecast(listOf(burst), now).getValue(1L)

        assertTrue(burstForecast.probability > stableForecast.probability)
    }
}
