package com.intervalcoach.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class WorkoutEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "intervals", foreignKeys = [ForeignKey(entity = WorkoutEntity::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workoutId")])
data class IntervalEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val workoutId: Long, val position: Int, val activity: String, val durationSeconds: Int, val instruction: String = "")

data class WorkoutWithIntervals(@Embedded val workout: WorkoutEntity, @Relation(parentColumn = "id", entityColumn = "workoutId") val rawIntervals: List<IntervalEntity>) {
    val intervals get() = rawIntervals.sortedWith(compareBy<IntervalEntity> { it.position }.thenBy { it.id })
    val totalSeconds get() = intervals.sumOf { it.durationSeconds }
}

@Dao
interface WorkoutDao {
    @Transaction @Query("SELECT * FROM workouts ORDER BY id DESC") fun observeAll(): Flow<List<WorkoutWithIntervals>>
    @Transaction @Query("SELECT * FROM workouts WHERE id = :id") fun observe(id: Long): Flow<WorkoutWithIntervals?>
    @Transaction @Query("SELECT * FROM workouts WHERE id = :id") suspend fun get(id: Long): WorkoutWithIntervals?
    @Insert suspend fun insertWorkout(workout: WorkoutEntity): Long
    @Update suspend fun updateWorkout(workout: WorkoutEntity)
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun deleteWorkout(id: Long)
    @Insert suspend fun insertInterval(interval: IntervalEntity): Long
    @Update suspend fun updateInterval(interval: IntervalEntity)
    @Query("DELETE FROM intervals WHERE id = :id") suspend fun deleteInterval(id: Long)
    @Query("UPDATE intervals SET position = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Int)
}

@Database(entities = [WorkoutEntity::class, IntervalEntity::class], version = 1, exportSchema = false)
abstract class CoachDatabase : RoomDatabase() { abstract fun dao(): WorkoutDao }

class WorkoutRepository(private val db: CoachDatabase) {
    val workouts = db.dao().observeAll()
    fun workout(id: Long) = db.dao().observe(id)
    suspend fun get(id: Long) = db.dao().get(id)
    suspend fun create(name: String) = db.dao().insertWorkout(WorkoutEntity(name = name.trim().ifEmpty { "New workout" }))
    suspend fun rename(id: Long, name: String) { db.dao().updateWorkout(WorkoutEntity(id, name.trim().ifEmpty { "New workout" })) }
    suspend fun delete(id: Long) = db.dao().deleteWorkout(id)
    suspend fun add(workoutId: Long, activity: String, seconds: Int, instruction: String) {
        require(seconds in 1..35999 && activity.isNotBlank())
        val next = get(workoutId)?.intervals?.size ?: 0
        db.dao().insertInterval(IntervalEntity(workoutId = workoutId, position = next, activity = activity.trim(), durationSeconds = seconds, instruction = instruction.trim()))
    }
    suspend fun edit(item: IntervalEntity, activity: String, seconds: Int, instruction: String) {
        require(seconds in 1..35999 && activity.isNotBlank())
        db.dao().updateInterval(item.copy(activity = activity.trim(), durationSeconds = seconds, instruction = instruction.trim()))
    }
    suspend fun duplicate(item: IntervalEntity) = db.withTransaction {
        val items = get(item.workoutId)?.intervals.orEmpty().toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items.add(index + 1, item.copy(id = 0))
            rewrite(item.workoutId, items)
        }
    }
    suspend fun remove(item: IntervalEntity) = db.withTransaction {
        db.dao().deleteInterval(item.id)
        get(item.workoutId)?.intervals?.forEachIndexed { index, interval -> db.dao().setPosition(interval.id, index) }
    }
    suspend fun move(workoutId: Long, from: Int, to: Int) = db.withTransaction {
        val items = get(workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        if (from !in items.indices || to !in items.indices || from == to) return@withTransaction
        items.add(to, items.removeAt(from))
        rewrite(workoutId, items)
    }
    private suspend fun rewrite(workoutId: Long, items: List<IntervalEntity>) {
        // Update existing rows and insert the duplicated row at its final position.
        items.forEachIndexed { position, item ->
            if (item.id == 0L) db.dao().insertInterval(item.copy(workoutId = workoutId, position = position))
            else db.dao().setPosition(item.id, position)
        }
    }
}
