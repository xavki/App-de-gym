package com.gymflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExerciseEntity::class,
        RoutineEntity::class, RoutineExerciseEntity::class, RoutineSetEntity::class,
        WorkoutEntity::class, WorkoutExerciseEntity::class, WorkoutSetEntity::class,
        BodyMeasurementEntity::class, ScheduleEntity::class, AchievementEntity::class,
        SyncRevEntity::class, SyncStateEntity::class, SyncConflictEntity::class, SyncArchiveEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun dao(): GymDao
    abstract fun syncDao(): SyncDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile private var instance: GymDatabase? = null

        fun get(context: Context): GymDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, "gymflow.db")
                .addMigrations(*Migrations.ALL)
                .build()
                .also { instance = it }
        }
    }
}
