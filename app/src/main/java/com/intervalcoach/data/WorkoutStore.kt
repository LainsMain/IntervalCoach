package com.intervalcoach.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class WorkoutEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "intervals", foreignKeys = [ForeignKey(entity = WorkoutEntity::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workoutId")])
data class IntervalEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val workoutId: Long, val position: Int, val activity: String, val durationSeconds: Int, val instruction: String = "", val isLoop: Boolean = false, val loopId: Long? = null, val repeatCount: Int = 1)

data class WorkoutWithIntervals(@Embedded val workout: WorkoutEntity, @Relation(parentColumn = "id", entityColumn = "workoutId") val rawIntervals: List<IntervalEntity>) {
    val intervals get() = rawIntervals.sortedWith(compareBy<IntervalEntity> { it.position }.thenBy { it.id })
    val blocks get() = intervals.filter { it.loopId == null }
    fun children(loopId: Long) = intervals.filter { it.loopId == loopId }
    val expandedIntervals: List<IntervalEntity> get() = buildList {
        blocks.forEach { block ->
            if (block.isLoop) repeat(block.repeatCount.coerceIn(2, 99)) { addAll(children(block.id)) }
            else add(block)
        }
    }
    val totalSeconds get() = expandedIntervals.sumOf { it.durationSeconds }
    val intervalCount get() = expandedIntervals.size
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
    @Query("UPDATE intervals SET loopId = :loopId WHERE id = :id") suspend fun setLoopId(id: Long, loopId: Long?)
}

@Database(entities = [WorkoutEntity::class, IntervalEntity::class], version = 2, exportSchema = false)
abstract class CoachDatabase : RoomDatabase() {
    abstract fun dao(): WorkoutDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intervals ADD COLUMN isLoop INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE intervals ADD COLUMN loopId INTEGER")
                db.execSQL("ALTER TABLE intervals ADD COLUMN repeatCount INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}

class WorkoutRepository(private val db: CoachDatabase) {
    val workouts = db.dao().observeAll()
    fun workout(id: Long) = db.dao().observe(id)
    suspend fun get(id: Long) = db.dao().get(id)
    suspend fun create(name: String) = db.dao().insertWorkout(WorkoutEntity(name = name.trim().ifEmpty { "New workout" }))
    suspend fun rename(id: Long, name: String) { db.dao().updateWorkout(WorkoutEntity(id, name.trim().ifEmpty { "New workout" })) }
    suspend fun delete(id: Long) = db.dao().deleteWorkout(id)
    suspend fun add(workoutId: Long, activity: String, seconds: Int, instruction: String, loopId: Long? = null) = db.withTransaction {
        require(seconds in 1..35999 && activity.isNotBlank())
        val rows = get(workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        if (loopId != null) require(rows.any { it.id == loopId && it.isLoop })
        val insertAt = if (loopId == null) rows.size else rows.indexOfLast { it.id == loopId || it.loopId == loopId } + 1
        val row = IntervalEntity(workoutId = workoutId, position = rows.size, activity = activity.trim(), durationSeconds = seconds, instruction = instruction.trim(), loopId = loopId)
        rows.add(insertAt, row.copy(id = db.dao().insertInterval(row)))
        rewrite(rows)
    }
    suspend fun addLoop(workoutId: Long) = db.withTransaction {
        val rows = get(workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        val header = IntervalEntity(workoutId = workoutId, position = rows.size, activity = "", durationSeconds = 0, isLoop = true, repeatCount = 8)
        val loopId = db.dao().insertInterval(header)
        rows += header.copy(id = loopId)
        listOf("Walk", "Run").forEach { activity ->
            val child = IntervalEntity(workoutId = workoutId, position = rows.size, activity = activity, durationSeconds = 60, loopId = loopId)
            rows += child.copy(id = db.dao().insertInterval(child))
        }
        rewrite(rows)
    }
    suspend fun wrapPair(first: IntervalEntity, second: IntervalEntity) = db.withTransaction {
        val rows = get(first.workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        val firstIndex = rows.indexOfFirst { it.id == first.id }
        if (firstIndex < 0 || rows.getOrNull(firstIndex + 1)?.id != second.id) return@withTransaction
        val currentFirst = rows[firstIndex]
        val currentSecond = rows[firstIndex + 1]
        if (currentFirst.isLoop || currentSecond.isLoop || currentFirst.loopId != null || currentSecond.loopId != null) return@withTransaction
        val header = IntervalEntity(workoutId = first.workoutId, position = rows.size, activity = "", durationSeconds = 0, isLoop = true, repeatCount = 8)
        val loopId = db.dao().insertInterval(header)
        db.dao().setLoopId(first.id, loopId)
        db.dao().setLoopId(second.id, loopId)
        rows[firstIndex] = rows[firstIndex].copy(loopId = loopId)
        rows[firstIndex + 1] = rows[firstIndex + 1].copy(loopId = loopId)
        rows.add(firstIndex, header.copy(id = loopId))
        rewrite(rows)
    }
    suspend fun edit(item: IntervalEntity, activity: String, seconds: Int, instruction: String) = db.withTransaction {
        require(!item.isLoop && seconds in 1..35999 && activity.isNotBlank())
        val current = get(item.workoutId)?.intervals?.firstOrNull { it.id == item.id } ?: return@withTransaction
        db.dao().updateInterval(current.copy(activity = activity.trim(), durationSeconds = seconds, instruction = instruction.trim()))
    }
    suspend fun setRepeats(item: IntervalEntity, repeats: Int) = db.withTransaction {
        require(item.isLoop && repeats in 2..99)
        val current = get(item.workoutId)?.intervals?.firstOrNull { it.id == item.id } ?: return@withTransaction
        db.dao().updateInterval(current.copy(repeatCount = repeats))
    }
    suspend fun duplicate(item: IntervalEntity) = db.withTransaction {
        val rows = get(item.workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        val index = rows.indexOfFirst { it.id == item.id }
        if (index < 0) return@withTransaction
        val source = rows[index]
        if (source.isLoop) {
            val header = source.copy(id = 0, position = rows.size)
            val newId = db.dao().insertInterval(header)
            val copies = mutableListOf(header.copy(id = newId))
            rows.filter { it.loopId == source.id }.forEach { child ->
                val copy = child.copy(id = 0, loopId = newId, position = rows.size + copies.size)
                copies += copy.copy(id = db.dao().insertInterval(copy))
            }
            rows.addAll(index + 1 + rows.count { it.loopId == source.id }, copies)
        } else {
            val copy = source.copy(id = 0, position = rows.size)
            rows.add(index + 1, copy.copy(id = db.dao().insertInterval(copy)))
        }
        rewrite(rows)
    }
    suspend fun remove(item: IntervalEntity) = db.withTransaction {
        val rows = get(item.workoutId)?.intervals ?: return@withTransaction
        val removedIds = if (item.isLoop) rows.filter { it.id == item.id || it.loopId == item.id }.map { it.id }.toSet() else setOf(item.id)
        removedIds.forEach { db.dao().deleteInterval(it) }
        rewrite(rows.filterNot { it.id in removedIds })
    }
    /** Indices refer either to top-level blocks or to children of the given loop. */
    suspend fun move(workoutId: Long, from: Int, to: Int, loopId: Long? = null) = db.withTransaction {
        val rows = get(workoutId)?.intervals?.toMutableList() ?: return@withTransaction
        val ids = if (loopId == null) rows.filter { it.loopId == null }.map { it.id }
            else rows.filter { it.loopId == loopId }.map { it.id }
        if (from !in ids.indices || to !in ids.indices || from == to) return@withTransaction
        if (loopId == null) {
            val groups = ids.map { id -> rows.filter { it.id == id || it.loopId == id } }
            rewrite(groups.toMutableList().apply { add(to, removeAt(from)) }.flatten())
        } else {
            val reordered = ids.toMutableList().apply { add(to, removeAt(from)) }
            val byId = rows.associateBy { it.id }
            val first = rows.indexOfFirst { it.loopId == loopId }
            if (first >= 0) {
                rows.removeAll { it.loopId == loopId }
                rows.addAll(first, reordered.map { byId.getValue(it) })
                rewrite(rows)
            }
        }
    }
    private suspend fun rewrite(rows: List<IntervalEntity>) {
        rows.forEachIndexed { position, row -> db.dao().setPosition(row.id, position) }
    }
}
