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
    @Test fun loopExpandsInOrderAndMovesAsOneBlock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CoachDatabase::class.java).build()
        try {
            val repo = WorkoutRepository(db)
            val id = repo.create("Repeats")
            repo.add(id, "Warmup", 300, "")
            repo.addLoop(id)
            var workout = repo.get(id)!!
            val loop = workout.blocks.last()
            assertEquals(8, loop.repeatCount)
            assertEquals(1260, workout.totalSeconds)
            assertEquals(17, workout.intervalCount)
            assertEquals(listOf("Warmup", "Walk", "Run", "Walk", "Run"), workout.expandedIntervals.take(5).map { it.activity })
            repo.move(id, 1, 0)
            workout = repo.get(id)!!
            assertEquals(listOf("Walk", "Run", "Walk", "Run"), workout.expandedIntervals.take(4).map { it.activity })
            assertEquals("Warmup", workout.expandedIntervals.last().activity)
            repo.move(id, 0, 1, loop.id)
            assertEquals(listOf("Run", "Walk", "Run", "Walk"), repo.get(id)!!.expandedIntervals.take(4).map { it.activity })
            repo.setRepeats(loop, 3)
            assertEquals(660, repo.get(id)!!.totalSeconds)
            repo.duplicate(loop)
            assertEquals(13, repo.get(id)!!.intervalCount)
            val copy = repo.get(id)!!.blocks[1]
            assertNotEquals(loop.id, copy.id)
            assertEquals(listOf("Run", "Walk"), repo.get(id)!!.children(copy.id).map { it.activity })
            repo.remove(loop)
            assertEquals(7, repo.get(id)!!.intervalCount)
            assertEquals(listOf(0, 1, 2, 3), repo.get(id)!!.intervals.map { it.position })
            repo.add(id, "Rest", 30, "Easy", copy.id)
            val withChild = repo.get(id)!!
            assertEquals(listOf("Run", "Walk", "Rest"), withChild.children(copy.id).map { it.activity })
            assertEquals(10, withChild.intervalCount)
            repo.edit(withChild.children(copy.id).first(), "Jog", 45, "Slow")
            assertEquals(listOf("Jog", "Walk", "Rest"), repo.get(id)!!.children(copy.id).map { it.activity })
        } finally { db.close() }
    }
    @Test fun wrapsExistingPairAndMigratesV1Data() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("migration-test.db")
        val old = context.openOrCreateDatabase("migration-test.db", 0, null)
        old.execSQL("CREATE TABLE workouts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
        old.execSQL("CREATE TABLE intervals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutId INTEGER NOT NULL, position INTEGER NOT NULL, activity TEXT NOT NULL, durationSeconds INTEGER NOT NULL, instruction TEXT NOT NULL, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_intervals_workoutId ON intervals (workoutId)")
        old.execSQL("INSERT INTO workouts (id, name) VALUES (1, 'Old run')")
        old.execSQL("INSERT INTO intervals (id, workoutId, position, activity, durationSeconds, instruction) VALUES (1, 1, 0, 'Walk', 60, '')")
        old.execSQL("INSERT INTO intervals (id, workoutId, position, activity, durationSeconds, instruction) VALUES (2, 1, 1, 'Run', 60, '')")
        old.version = 1
        old.close()
        val db = Room.databaseBuilder(context, CoachDatabase::class.java, "migration-test.db").addMigrations(CoachDatabase.MIGRATION_1_2).build()
        try {
            val repo = WorkoutRepository(db)
            var workout = repo.get(1)!!
            assertEquals(120, workout.totalSeconds)
            assertEquals(listOf("Walk", "Run"), workout.blocks.map { it.activity })
            repo.wrapPair(workout.blocks[0], workout.blocks[1])
            workout = repo.get(1)!!
            assertEquals(1, workout.blocks.size)
            assertTrue(workout.blocks.single().isLoop)
            assertEquals(960, workout.totalSeconds)
        } finally { db.close(); context.deleteDatabase("migration-test.db") }
    }
}
