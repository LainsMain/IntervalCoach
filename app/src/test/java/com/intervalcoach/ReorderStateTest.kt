package com.intervalcoach

import org.junit.Assert.*
import org.junit.Test

class ReorderStateTest {
    @Test fun draggingAcrossMultipleRowsCommitsOneMove() {
        val state = ReorderState(listOf(1L, 2L, 3L, 4L), 100f)
        val moves = mutableListOf<Pair<Int, Int>>()
        state.onCommit = { from, to -> moves += from to to }
        state.start(1)
        state.drag(260f)
        assertEquals(listOf(2L, 3L, 1L, 4L), state.order.toList())
        state.end()
        assertEquals(listOf(0 to 2), moves)
        state.sync(listOf(1L, 2L, 3L, 4L)) // The database has not emitted the move yet.
        assertEquals(listOf(2L, 3L, 1L, 4L), state.order.toList())
        state.sync(listOf(2L, 3L, 1L, 4L))
        assertTrue(state.moveDirect(4, -1))
        assertEquals(3 to 2, moves.last())
    }
}
