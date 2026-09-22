package org.effekthasch.quickshopper3k.domain.statistics

import java.util.Calendar

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


data class PurchaseHistory(
    val itemId: Long,
    val observedFrom: Long,
    val activations: List<Long>
)

data class PurchaseForecast(
    val probability: Double,
    val confidence: Double
)

data class PurchaseInsights(
    val histories: Map<Long, List<Long>>,
    val forecasts: Map<Long, PurchaseForecast>
)

object PurchaseStatistics {
    const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    const val DIURNAL_BOUNDARY_MILLIS = 4 * 60 * 60 * 1000L
    private const val PRIOR_DAYS = 90.0
    private const val RECENT_DAYS = 42.0
    private const val SEASONAL_BAND_DAYS = 30.0
    private const val MIN_RENEWAL_INTERVAL_DAYS = 2.0

    fun forecast(
        histories: List<PurchaseHistory>,
        now: Long,
        horizonDays: Int = 2
    ): Map<Long, PurchaseForecast> {
        if (histories.isEmpty()) return emptyMap()

        val horizon = horizonDays.coerceAtLeast(1)
        val dayParts = DayPartsCalculator()
        val preparedHistories = histories.map { history ->
            PreparedHistory(
                history = history,
                activations = history.activations.map { timestamp ->
                    PreparedActivation(timestamp, dayParts.at(timestamp))
                }
            )
        }
        val nowDay = dayParts.at(now)
        val horizonWeekdays = IntArray(horizon) { offset ->
            dayParts.at(now + offset * MILLIS_PER_DAY).dayOfWeek
        }
        val bulkDays = bulkDays(preparedHistories, now)
        val samples = preparedHistories.map { sample(it, now, bulkDays) }
        val totalObservedDays = samples.sumOf { it.observedDays }
        val totalEvents = samples.sumOf { it.events.size }
        val pooledRate = if (totalObservedDays > 0) {
            totalEvents / totalObservedDays
        } else {
            0.0
        }
        val weekdayFactors = weekdayFactors(samples)
        val horizonWeekdayFactor = weekdayFactor(weekdayFactors, horizonWeekdays)

        return samples.associate { sample ->
            sample.history.itemId to forecastSample(
                sample,
                pooledRate,
                horizonWeekdayFactor,
                nowDay,
                now,
                horizon
            )
        }
    }

    private fun forecastSample(
        sample: Sample,
        pooledRate: Double,
        horizonWeekdayFactor: Double,
        nowDay: DayParts,
        now: Long,
        horizonDays: Int
    ): PurchaseForecast {
        val baselineRate = (sample.events.size + pooledRate * PRIOR_DAYS) /
                (sample.observedDays + PRIOR_DAYS)
        val burstFactor = burstFactor(sample, now)
        val seasonalFactor = seasonalFactor(sample, nowDay)
        val adjustedRate = baselineRate * burstFactor * seasonalFactor * horizonWeekdayFactor
        val baselineProbability = probabilityFromRate(adjustedRate, horizonDays)

        val renewal = renewalProbability(sample, now, horizonDays)
        val probability = if (renewal == null) {
            baselineProbability
        } else {
            baselineProbability * (1.0 - renewal.second) + renewal.first * renewal.second
        }

        val confidence = if (sample.events.isEmpty()) {
            0.0
        } else {
            val eventEvidence = min(1.0, sample.events.size / 5.0)
            val observationEvidence = min(1.0, sample.observedDays / 180.0)
            val intervalEvidence = if (sample.events.size >= 3) {
                min(1.0, (sample.events.size - 1) / 4.0)
            } else {
                0.0
            }
            eventEvidence * observationEvidence * (0.5 + 0.5 * intervalEvidence)
        }

        return PurchaseForecast(
            probability = probability.coerceIn(0.0, 0.95),
            confidence = confidence.coerceIn(0.0, 1.0)
        )
    }

    private fun sample(
        history: PreparedHistory,
        now: Long,
        bulkDays: Set<Int>
    ): Sample {
        val observedFrom = min(history.history.observedFrom, now)
        val activations = ArrayList<PreparedActivation>(history.activations.size)
        val seen = HashSet<Long>()
        var sorted = true
        var previousTimestamp: Long? = null

        for (activation in history.activations) {
            val timestamp = activation.timestamp
            if (timestamp < observedFrom || timestamp > now) continue
            if (!seen.add(timestamp)) continue

            val previous = previousTimestamp
            if (previous != null && timestamp < previous) sorted = false
            previousTimestamp = timestamp
            if (activation.day.key !in bulkDays) activations.add(activation)
        }
        if (!sorted) activations.sortBy { it.timestamp }

        val firstActivation = activations.firstOrNull()
        val hasInitialActivation = firstActivation != null &&
                firstActivation.timestamp - observedFrom <= MILLIS_PER_DAY
        val events = if (hasInitialActivation) {
            activations.subList(1, activations.size)
        } else {
            activations
        }
        val observedDays = max(
            1.0,
            (now - observedFrom).toDouble() / MILLIS_PER_DAY
        )
        return Sample(history.history, activations, events, observedDays)
    }

    private fun burstFactor(sample: Sample, now: Long): Double {
        if (sample.events.isEmpty()) return 1.0
        val observedDays = sample.observedDays
        val recentDays = min(observedDays, RECENT_DAYS)
        val recentStart = now - (recentDays * MILLIS_PER_DAY).toLong()
        var recentEvents = 0
        for (event in sample.events) {
            if (event.timestamp >= recentStart) recentEvents++
        }
        val recentRate = recentEvents / recentDays
        val longRate = sample.events.size / observedDays
        val ratio = (recentRate / max(longRate, 1e-9)).coerceIn(0.25, 4.0)
        val evidence = min(1.0, sample.events.size / 4.0)
        return exp(0.35 * ln(ratio) * evidence)
    }

    private fun seasonalFactor(sample: Sample, nowDay: DayParts): Double {
        if (sample.events.size < 3 || sample.observedDays < 730.0) return 1.0
        val firstEvent = sample.events.firstOrNull() ?: return 1.0
        val lastEvent = sample.events.lastOrNull() ?: return 1.0
        if (firstEvent.day.year == lastEvent.day.year) return 1.0

        var weightedEvents = 0.0
        for (event in sample.events) {
            val distance = circularDayDistance(event.day.dayOfYear, nowDay.dayOfYear)
            weightedEvents += exp(-distance / SEASONAL_BAND_DAYS)
        }
        val expected = sample.events.size * (2.0 * SEASONAL_BAND_DAYS / 365.25)
        val ratio = (weightedEvents / max(expected, 1e-9)).coerceIn(0.25, 4.0)
        val evidence = min(1.0, sample.observedDays / 730.0)
        return exp(0.25 * ln(ratio) * evidence).coerceIn(0.7, 1.4)
    }

    private fun weekdayFactors(samples: List<Sample>): DoubleArray {
        val counts = DoubleArray(7)
        for (sample in samples) {
            for (event in sample.events) counts[event.day.dayOfWeek]++
        }
        val prior = 4.0
        val total = counts.sum() + prior * counts.size
        val expected = total / counts.size
        return DoubleArray(7) { index -> (counts[index] + prior) / expected }
    }

    private fun weekdayFactor(factors: DoubleArray, weekdays: IntArray): Double {
        var sum = 0.0
        for (weekday in weekdays) sum += factors[weekday]
        return (sum / weekdays.size).coerceIn(0.75, 1.5)
    }

    private fun renewalProbability(
        sample: Sample,
        now: Long,
        horizonDays: Int
    ): Pair<Double, Double>? {
        val renewalEvents = collapseShortClusters(sample.events)
        if (renewalEvents.size < 3) return null

        val logarithms = DoubleArray(renewalEvents.size - 1)
        var intervalCount = 0
        for (index in 1 until renewalEvents.size) {
            val interval = (renewalEvents[index].timestamp - renewalEvents[index - 1].timestamp)
                .toDouble() / MILLIS_PER_DAY
            if (interval > 0.0) logarithms[intervalCount++] = ln(interval)
        }
        if (intervalCount == 0) return null

        var mean = 0.0
        for (index in 0 until intervalCount) mean += logarithms[index]
        mean /= intervalCount
        var variance = 0.49
        if (intervalCount > 1) {
            var squaredDeviation = 0.0
            for (index in 0 until intervalCount) {
                val difference = logarithms[index] - mean
                squaredDeviation += difference * difference
            }
            variance = squaredDeviation / (intervalCount - 1)
        }
        val deviation = sqrt(variance).coerceAtLeast(0.25)
        val lastEvent = renewalEvents.lastOrNull() ?: return null
        val age = max(0.25, (now - lastEvent.timestamp).toDouble() / MILLIS_PER_DAY)
        val end = age + horizonDays
        val survival = (1.0 - normalCdf((ln(age) - mean) / deviation)).coerceAtLeast(1e-9)
        val probability = (
            normalCdf((ln(end) - mean) / deviation) -
                    normalCdf((ln(age) - mean) / deviation)
            ) / survival
        val evidence = min(1.0, intervalCount / 5.0) * (1.0 / (1.0 + deviation))
        return probability.coerceIn(0.0, 1.0) to evidence
    }

    private fun probabilityFromRate(ratePerDay: Double, horizonDays: Int): Double {
        return 1.0 - exp(-ratePerDay * horizonDays)
    }

    private fun collapseShortClusters(events: List<PreparedActivation>): List<PreparedActivation> {
        if (events.isEmpty()) return emptyList()
        val collapsed = ArrayList<PreparedActivation>(events.size)
        val first = events.firstOrNull() ?: return emptyList()
        collapsed.add(first)
        for (index in 1 until events.size) {
            val event = events[index]
            val distance = (event.timestamp - collapsed.last().timestamp).toDouble() / MILLIS_PER_DAY
            if (distance < MIN_RENEWAL_INTERVAL_DAYS) {
                collapsed[collapsed.lastIndex] = event
            } else {
                collapsed.add(event)
            }
        }
        return collapsed
    }

    private fun bulkDays(histories: List<PreparedHistory>, now: Long): Set<Int> {
        val itemsByDay = mutableMapOf<Int, MutableSet<Long>>()
        for (history in histories) {
            for (activation in history.activations) {
                if (activation.timestamp <= now) {
                    itemsByDay.getOrPut(activation.day.key) { mutableSetOf() }
                        .add(history.history.itemId)
                }
            }
        }
        val threshold = maxOf(20, ceil(histories.size * 0.25).toInt())
        return itemsByDay
            .filterValues { it.size >= threshold }
            .keys
    }

    private fun circularDayDistance(first: Int, second: Int): Int {
        val distance = abs(first - second)
        return min(distance, 366 - distance)
    }

    private fun normalCdf(value: Double): Double {
        val sign = if (value < 0.0) -1.0 else 1.0
        val x = abs(value) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val polynomial = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t -
                0.284496736) * t + 0.254829592) * t
        val erf = 1.0 - polynomial * exp(-x * x)
        return 0.5 * (1.0 + sign * erf)
    }

    private class DayPartsCalculator {
        private val calendar = Calendar.getInstance()

        fun at(timestamp: Long): DayParts {
            calendar.timeInMillis = timestamp - DIURNAL_BOUNDARY_MILLIS
            val year = calendar.get(Calendar.YEAR)
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            return DayParts(
                key = year * 400 + dayOfYear,
                year = year,
                dayOfYear = dayOfYear,
                dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            )
        }
    }

    private data class DayParts(
        val key: Int,
        val year: Int,
        val dayOfYear: Int,
        val dayOfWeek: Int
    )

    private data class PreparedActivation(
        val timestamp: Long,
        val day: DayParts
    )

    private data class PreparedHistory(
        val history: PurchaseHistory,
        val activations: List<PreparedActivation>
    )

    private data class Sample(
        val history: PurchaseHistory,
        val activations: List<PreparedActivation>,
        val events: List<PreparedActivation>,
        val observedDays: Double
    )
}
