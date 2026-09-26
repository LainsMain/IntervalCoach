package com.intervalcoach

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Keeps drag feedback local, then writes a single ordered move when the finger lifts. */
class ReorderState(ids: List<Long>, private val fallbackHeightPx: Float) {
    val order = mutableStateListOf<Long>().apply { addAll(ids) }
    private val heights = mutableStateMapOf<Long, Int>()
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var onCommit: (Int, Int) -> Unit = { _, _ -> }
    private var startIndex = -1
    private var awaitingWrite = false
    private var sourceBeforeDrag: List<Long> = ids
    private var pendingSource: List<Long>? = null

    fun sync(ids: List<Long>) {
        if (draggingId != null) return
        if (awaitingWrite && ids == pendingSource) return
        awaitingWrite = false
        pendingSource = null
        if (ids != order.toList()) { order.clear(); order.addAll(ids) }
    }
    fun height(id: Long, pixels: Int) { heights[id] = pixels }
    fun start(id: Long) {
        startIndex = order.indexOf(id)
        if (startIndex >= 0) { sourceBeforeDrag = order.toList(); draggingId = id; offsetPx = 0f }
    }
    fun drag(delta: Float) {
        val id = draggingId ?: return
        offsetPx += delta
        // Repeated swaps also handle a fast gesture delivered in one pointer event.
        repeat(order.size) {
            val index = order.indexOf(id)
            val next = if (offsetPx > 0) index + 1 else index - 1
            if (next !in order.indices) return
            val neighborHeight = (heights[order[next]]?.toFloat() ?: fallbackHeightPx)
            val ownHeight = (heights[id]?.toFloat() ?: fallbackHeightPx)
            if (kotlin.math.abs(offsetPx) < (ownHeight + neighborHeight) * 0.35f) return
            order.add(next, order.removeAt(index))
            offsetPx += if (next > index) -neighborHeight else neighborHeight
        }
    }
    fun end() {
        val id = draggingId ?: return
        val endIndex = order.indexOf(id)
        draggingId = null
        offsetPx = 0f
        if (startIndex >= 0 && endIndex != startIndex) {
            awaitingWrite = true
            pendingSource = sourceBeforeDrag
            onCommit(startIndex, endIndex)
        }
    }
    fun moveDirect(id: Long, change: Int): Boolean {
        val from = order.indexOf(id)
        val to = from + change
        if (from !in order.indices || to !in order.indices) return false
        pendingSource = order.toList()
        order.add(to, order.removeAt(from))
        awaitingWrite = true
        onCommit(from, to)
        return true
    }
    fun cancel() { draggingId = null; offsetPx = 0f; awaitingWrite = false; pendingSource = null }
}

@Composable
fun rememberReorderState(key: Any, ids: List<Long>, fallbackHeightPx: Float, onCommit: (Int, Int) -> Unit): ReorderState {
    val state = remember(key) { ReorderState(ids, fallbackHeightPx) }
    SideEffect { state.onCommit = onCommit; state.sync(ids) }
    return state
}
