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
        val bulkDays = bulkDays(histories, now)
        val samples = histories.map { sample(it, now, bulkDays) }
        val totalObservedDays = samples.sumOf { it.observedDays }
        val totalEvents = samples.sumOf { it.events.size }
        val pooledRate = if (totalObservedDays > 0) {
            totalEvents / totalObservedDays
        } else {
            0.0
        }
        val weekdayFactors = weekdayFactors(samples.flatMap { it.events })

        return samples.associate { sample ->
            sample.history.itemId to forecastSample(
                sample,
                pooledRate,
                weekdayFactors,
                now,
                horizon
            )
        }
    }

    private fun forecastSample(
        sample: Sample,
        pooledRate: Double,
        weekdayFactors: DoubleArray,
        now: Long,
        horizonDays: Int
    ): PurchaseForecast {
        val baselineRate = (sample.events.size + pooledRate * PRIOR_DAYS) /
                (sample.observedDays + PRIOR_DAYS)
        val burstFactor = burstFactor(sample, now)
        val seasonalFactor = seasonalFactor(sample, now)
        val weekdayFactor = weekdayFactor(weekdayFactors, now, horizonDays)
        val adjustedRate = baselineRate * burstFactor * seasonalFactor * weekdayFactor
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
        history: PurchaseHistory,
        now: Long,
        bulkDays: Set<Int>
    ): Sample {
        val observedFrom = min(history.observedFrom, now)
        val activations = history.activations
            .asSequence()
            .filter { it >= observedFrom && it <= now }
            .filterNot { dayKey(it) in bulkDays }
            .distinct()
            .sorted()
            .toList()
        val hasInitialActivation = activations.firstOrNull()?.let {
            it - observedFrom <= MILLIS_PER_DAY
        } == true
        val events = if (hasInitialActivation) activations.drop(1) else activations
        val observedDays = max(
            1.0,
            (now - observedFrom).toDouble() / MILLIS_PER_DAY
        )
        return Sample(history, activations, events, observedDays)
    }

    private fun burstFactor(sample: Sample, now: Long): Double {
        if (sample.events.isEmpty()) return 1.0
        val observedDays = sample.observedDays
        val recentDays = min(observedDays, RECENT_DAYS)
        val recentStart = now - (recentDays * MILLIS_PER_DAY).toLong()
        val recentRate = sample.events.count { it >= recentStart } / recentDays
        val longRate = sample.events.size / observedDays
        val ratio = (recentRate / max(longRate, 1e-9)).coerceIn(0.25, 4.0)
        val evidence = min(1.0, sample.events.size / 4.0)
        return exp(0.35 * ln(ratio) * evidence)
    }

    private fun seasonalFactor(sample: Sample, now: Long): Double {
        if (sample.events.size < 3 || sample.observedDays < 730.0) return 1.0
        if (sample.events.map { calendar(it).get(Calendar.YEAR) }.distinct().size < 2) return 1.0

        val weightedEvents = sample.events.sumOf { event ->
            val distance = circularDayDistance(dayOfYear(event), dayOfYear(now))
            exp(-distance / SEASONAL_BAND_DAYS)
        }
        val expected = sample.events.size * (2.0 * SEASONAL_BAND_DAYS / 365.25)
        val ratio = (weightedEvents / max(expected, 1e-9)).coerceIn(0.25, 4.0)
        val evidence = min(1.0, sample.observedDays / 730.0)
        return exp(0.25 * ln(ratio) * evidence).coerceIn(0.7, 1.4)
    }

    private fun weekdayFactors(events: List<Long>): DoubleArray {
        val counts = DoubleArray(7)
        events.forEach { event -> counts[dayOfWeek(event)]++ }
        val prior = 4.0
        val total = counts.sum() + prior * counts.size
        val expected = total / counts.size
        return DoubleArray(7) { index -> (counts[index] + prior) / expected }
    }

    private fun weekdayFactor(factors: DoubleArray, now: Long, horizonDays: Int): Double {
        val sum = (0 until horizonDays).sumOf { offset ->
            factors[dayOfWeek(now + offset * MILLIS_PER_DAY)]
        }
        return (sum / horizonDays).coerceIn(0.75, 1.5)
    }

    private fun renewalProbability(
        sample: Sample,
        now: Long,
        horizonDays: Int
    ): Pair<Double, Double>? {
        val renewalEvents = collapseShortClusters(sample.events)
        if (renewalEvents.size < 3) return null

        val intervals = renewalEvents.zipWithNext()
            .map { (before, after) -> (after - before).toDouble() / MILLIS_PER_DAY }
            .filter { it > 0.0 }
        if (intervals.isEmpty()) return null

        val logarithms = intervals.map { ln(it) }
        val mean = logarithms.average()
        val variance = if (logarithms.size == 1) {
            0.49
        } else {
            logarithms.sumOf { (it - mean) * (it - mean) } / (logarithms.size - 1)
        }
        val deviation = sqrt(variance).coerceAtLeast(0.25)
        val age = max(0.25, (now - renewalEvents.last()).toDouble() / MILLIS_PER_DAY)
        val end = age + horizonDays
        val survival = (1.0 - normalCdf((ln(age) - mean) / deviation)).coerceAtLeast(1e-9)
        val probability = (
            normalCdf((ln(end) - mean) / deviation) -
                    normalCdf((ln(age) - mean) / deviation)
            ) / survival
        val evidence = min(1.0, intervals.size / 5.0) * (1.0 / (1.0 + deviation))
        return probability.coerceIn(0.0, 1.0) to evidence
    }

    private fun probabilityFromRate(ratePerDay: Double, horizonDays: Int): Double {
        return 1.0 - exp(-ratePerDay * horizonDays)
    }

    private fun collapseShortClusters(events: List<Long>): List<Long> {
        if (events.isEmpty()) return emptyList()
        val collapsed = mutableListOf(events.first())
        events.drop(1).forEach { event ->
            val distance = (event - collapsed.last()).toDouble() / MILLIS_PER_DAY
            if (distance < MIN_RENEWAL_INTERVAL_DAYS) {
                collapsed[collapsed.lastIndex] = event
            } else {
                collapsed.add(event)
            }
        }
        return collapsed
    }

    private fun bulkDays(histories: List<PurchaseHistory>, now: Long): Set<Int> {
        val itemsByDay = mutableMapOf<Int, MutableSet<Long>>()
        histories.forEach { history ->
            history.activations
                .asSequence()
                .filter { it <= now }
                .forEach { activation ->
                    itemsByDay.getOrPut(dayKey(activation)) { mutableSetOf() }.add(history.itemId)
                }
        }
        val threshold = maxOf(20, ceil(histories.size * 0.25).toInt())
        return itemsByDay
            .filterValues { it.size >= threshold }
            .keys
    }

    private fun dayOfYear(timestamp: Long): Int = calendar(timestamp).get(Calendar.DAY_OF_YEAR)

    private fun dayOfWeek(timestamp: Long): Int = calendar(timestamp).get(Calendar.DAY_OF_WEEK) - 1

    private fun dayKey(timestamp: Long): Int {
        val date = calendar(timestamp)
        return date.get(Calendar.YEAR) * 400 + date.get(Calendar.DAY_OF_YEAR)
    }

    private fun calendar(timestamp: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = timestamp - DIURNAL_BOUNDARY_MILLIS
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

    private data class Sample(
        val history: PurchaseHistory,
        val activations: List<Long>,
        val events: List<Long>,
        val observedDays: Double
    )


}
