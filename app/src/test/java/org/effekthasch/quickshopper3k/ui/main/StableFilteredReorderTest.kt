package org.effekthasch.quickshopper3k.ui.main

import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val GAP = 1 shl 10

private val CASES = listOf(
    listOf("end to between", listOf(1, 2, -3, -4, 5), listOf(5, "before", 2), listOf(1, 5, 2, -3, -4)),
    listOf("middle to end", listOf(1, 2, -3, -4, 5), listOf(2, "after", 5), listOf(1, -3, -4, 5, 2)),
    listOf("middle to start", listOf(1, 2, -3, -4, 5), listOf(2, "before", 1), listOf(2, 1, -3, -4, 5)),
    listOf("to same location", listOf(1, 2, -3, -4, 5), listOf(2, "after", 1), listOf(1, 2, -3, -4, 5)),
    listOf("before hidden section", listOf(1, 2, 3, -4, -5, 6), listOf(2, "after", 3), listOf(1, 3, 2, -4, -5, 6)),
    listOf("after hidden section", listOf(1, 2, 3, -4, -5, 6), listOf(2, "before", 6), listOf(1, 3, 2, -4, -5, 6)),
)

private fun item(id: Long, sortOrder: Int, state: ItemState = ItemState.ACTIVATED) = StoreItem(
    item = Item(
        id = id,
        uuid = "u$id",
        name = "item$id",
        state = state,
        createdAt = 0L
    ),
    sortOrder = sortOrder,
    isAvailable = true
)

private fun sortedIds(items: List<StoreItem>): List<Long> =
    items.sortedWith(compareBy<StoreItem> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.item.id })
        .map { it.item.id }

private fun findMovedItemId(previous: List<StoreItem>, currentVisible: List<Long>): Long? {
    if (currentVisible.size < 2) return null
    val prevVisible = previous.map { it.item.id }.filter { currentVisible.contains(it) }
    if (prevVisible.size != currentVisible.size) return null

    val moved = currentVisible.filter { id ->
        val oldIndex = prevVisible.indexOf(id)
        val newIndex = currentVisible.indexOf(id)
        oldIndex != -1 && oldIndex != newIndex
    }

    return if (moved.size == 1) moved.first() else moved.lastOrNull()
}

private fun computeGappedOrder(upper: Int?, lower: Int?, current: Int): Int? {
    return when {
        upper == null && lower == null -> current
        upper == null -> {
            val candidate = lower!! - GAP
            if (candidate < Int.MIN_VALUE / 2) null else candidate
        }

        lower == null -> {
            val candidate = upper + GAP
            if (candidate > Int.MAX_VALUE / 2) null else candidate
        }

        else -> {
            val gap = lower - upper
            if (gap <= 1) null else upper + gap / 2
        }
    }
}

private fun renumberAllWithPowerOfTwoGap(
    sorted: List<StoreItem>,
    movedItemId: Long,
    upperId: Long?
): List<Pair<Long, Int>> {
    val ids = sorted.map { it.item.id }.toMutableList()

    ids.remove(movedItemId)
    val insertAt = if (upperId == null) 0 else {
        val idx = ids.indexOf(upperId)
        if (idx == -1) 0 else idx + 1
    }
    ids.add(insertAt, movedItemId)

    return ids.mapIndexed { index, id -> id to ((index + 1) * GAP) }
}

private fun buildItems(encoded: List<*>): List<StoreItem> =
    encoded.mapIndexed { idx, raw ->
        val raw = raw as Int
        val id = kotlin.math.abs(raw).toLong()
        val state = if (raw < 0) ItemState.DEACTIVATED else ItemState.ACTIVATED
        item(id, (idx + 1) * GAP, state)
    }

private fun visibleIds(encoded: List<*>): List<Long> =
    encoded.map { it as Int }.filter { it > 0 }.map { it.toLong() }

private fun buildVisibleAfterDrag(before: List<*>, move: List<*>): List<Long> {
    val movedId = (move[0] as Int).toLong()
    val direction = move[1] as String
    val targetId = (move[2] as Int).toLong()
    val visible = visibleIds(before).toMutableList()
    visible.remove(movedId)
    val targetIdx = visible.indexOf(targetId)
    val insertAt = when (direction) {
        "before" -> targetIdx
        "after" -> targetIdx + 1
        else -> error("unknown direction: $direction")
    }
    visible.add(insertAt, movedId)
    return visible
}

private fun expectedAllIds(encoded: List<*>): List<Long> =
    encoded.map { kotlin.math.abs(it as Int).toLong() }

private fun runReorder(all: List<StoreItem>, visibleAfterDrag: List<Long>, movedItemId: Long): List<StoreItem> {
    val sorted = all.sortedWith(compareBy<StoreItem> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.item.id })
    val sortOrderById = all.associate { it.item.id to (it.sortOrder ?: 0) }

    val movedIndex = visibleAfterDrag.indexOf(movedItemId)
    val upperVisibleId = if (movedIndex > 0) visibleAfterDrag[movedIndex - 1] else null
    val lowerVisibleId = if (movedIndex + 1 < visibleAfterDrag.size) visibleAfterDrag[movedIndex + 1] else null

    val insertAfterIdx = upperVisibleId?.let { uid -> sorted.indexOfFirst { it.item.id == uid } }
    val insertBeforeIdx = if (insertAfterIdx != null) {
        ((insertAfterIdx + 1) until sorted.size).firstOrNull { sorted[it].item.id != movedItemId }
    } else {
        lowerVisibleId?.let { lid -> sorted.indexOfFirst { it.item.id == lid } }
            ?.let { idx -> (idx until sorted.size).firstOrNull { sorted[it].item.id != movedItemId } }
    }

    val upperOrder = insertAfterIdx?.let { sortOrderById[sorted[it].item.id] }
    val lowerOrder = insertBeforeIdx?.let { sortOrderById[sorted[it].item.id] }
    val movedCurrent = sortOrderById[movedItemId] ?: 0

    val computed = computeGappedOrder(upperOrder, lowerOrder, movedCurrent)

    return if (computed == null) {
        val renumbered = renumberAllWithPowerOfTwoGap(sorted, movedItemId, upperVisibleId)
        val byNewOrder = renumbered.sortedBy { it.second }
        byNewOrder.map { (id, order) ->
            val original = all.first { it.item.id == id }
            original.copy(sortOrder = order)
        }
    } else {
        all.map { if (it.item.id == movedItemId) it.copy(sortOrder = computed) else it }
    }
}

@RunWith(Parameterized::class)
class StableFilteredReorderTest(
    private val label: String,
    private val before: List<*>,
    private val move: List<*>,
    private val after: List<*>
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = CASES.map { c ->
            val label = c[0] as String
            val before = c[1] as List<*>
            val move = c[2] as List<*>
            val after = c[3] as List<*>
            arrayOf(
                label as Any,
                before as Any,
                move as Any,
                after as Any
            )
        }
    }

    @Test
    fun reorderProducesExpectedResult() {
        val items = buildItems(before)
        val visibleAfterDrag = buildVisibleAfterDrag(before, move)
        val movedItemId = (move[0] as Int).toLong()
        val updated = runReorder(items, visibleAfterDrag, movedItemId)
        assertEquals(expectedAllIds(after), sortedIds(updated))
    }
}
