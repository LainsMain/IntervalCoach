package com.intervalcoach.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutDaoTest {
    @Test fun survivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("reopen-test.db")
        var db = Room.databaseBuilder(context, CoachDatabase::class.java, "reopen-test.db").build()
        val id = WorkoutRepository(db).create("Saved run")
        WorkoutRepository(db).add(id, "Hill climb", 91, "Steady")
        db.close()
        db = Room.databaseBuilder(context, CoachDatabase::class.java, "reopen-test.db").build()
        try {
            val saved = WorkoutRepository(db).get(id)!!
            assertEquals("Saved run", saved.workout.name)
            assertEquals(91, saved.totalSeconds)
            assertEquals("Hill climb", saved.intervals.single().activity)
        } finally { db.close(); context.deleteDatabase("reopen-test.db") }
    }
    @Test fun persistsOrderingAndTotal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CoachDatabase::class.java).build()
        try {
            val repo = WorkoutRepository(db)
            val id = repo.create("Weekend Run")
            repo.add(id, "Walk", 300, "Slow")
            repo.add(id, "Run", 60, "")
            val first = repo.get(id)!!
            assertEquals(360, first.totalSeconds)
            assertEquals(listOf("Walk", "Run"), first.intervals.map { it.activity })
            repo.move(id, 0, 1)
            assertEquals(listOf("Run", "Walk"), repo.get(id)!!.intervals.map { it.activity })
            repo.duplicate(repo.get(id)!!.intervals.first())
            assertEquals(listOf("Run", "Run", "Walk"), repo.get(id)!!.intervals.map { it.activity })
            repo.remove(repo.get(id)!!.intervals[1])
            assertEquals(listOf(0, 1), repo.get(id)!!.intervals.map { it.position })
        } finally { db.close() }
    }
}
