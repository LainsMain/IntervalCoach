package com.intervalcoach

import android.app.Application
import androidx.room.Room
import com.intervalcoach.data.CoachDatabase
import com.intervalcoach.data.WorkoutRepository
import com.intervalcoach.session.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

class CoachApp : Application() {
    lateinit var repository: WorkoutRepository
        private set
    val session = MutableStateFlow<SessionSnapshot?>(null)
    override fun onCreate() {
        super.onCreate()
        repository = WorkoutRepository(Room.databaseBuilder(this, CoachDatabase::class.java, "workouts.db").build())
    }
}
