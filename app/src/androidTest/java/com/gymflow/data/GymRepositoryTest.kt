package com.gymflow.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymflow.BodyMeasurement
import com.gymflow.ExerciseSet
import com.gymflow.SetType
import com.gymflow.WorkoutExercise
import com.gymflow.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GymRepositoryTest {

    private val uid = "user-1"
    private lateinit var context: Context
    private lateinit var db: GymDatabase
    private lateinit var repo: GymRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        repo = GymRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun routine() = WorkoutSession(
        name = "Push",
        exercises = mutableListOf(
            WorkoutExercise(exerciseName = "Bench Press", sets = mutableListOf(
                ExerciseSet(weight = 60.0, repetitions = 10),
                ExerciseSet(weight = 70.0, repetitions = 8)
            )),
            WorkoutExercise(exerciseName = "Overhead Press", sets = mutableListOf(
                ExerciseSet(weight = 40.0, repetitions = 10)
            ))
        )
    )

    @Test fun routineRoundTripKeepsOrder() = runBlocking {
        val r = routine()
        repo.saveRoutine(uid, r)
        val loaded = repo.routines(uid).single()
        assertEquals("Push", loaded.name)
        assertEquals(listOf("Bench Press", "Overhead Press"), loaded.exercises.map { it.exerciseName })
        assertEquals(listOf(60.0, 70.0), loaded.exercises[0].sets.map { it.weight })
    }

    @Test fun editingRoutineOnlyTouchesChangedRowsAndSoftDeletesRemoved() = runBlocking {
        val r = routine()
        repo.saveRoutine(uid, r)
        val before = db.dao().routineSetsOf(r.id).associateBy { it.id }
        Thread.sleep(10)

        // Quita Overhead Press y cambia el peso de la 2ª serie de banca
        val edited = r.copy(exercises = mutableListOf(r.exercises[0].copy(
            sets = mutableListOf(r.exercises[0].sets[0], r.exercises[0].sets[1].copy(weight = 72.5))
        )))
        repo.saveRoutine(uid, edited)

        val exRows = db.dao().routineExercisesOf(r.id).associateBy { it.exerciseName }
        assertNull(exRows["Bench Press"]!!.deletedAt)
        assertNotNull("el ejercicio quitado queda como borrado lógico", exRows["Overhead Press"]!!.deletedAt)

        val after = db.dao().routineSetsOf(r.id).associateBy { it.id }
        val untouched = r.exercises[0].sets[0].id
        val changed = r.exercises[0].sets[1].id
        assertEquals(before[untouched]!!.updatedAt, after[untouched]!!.updatedAt)
        assertNotEquals(before[changed]!!.updatedAt, after[changed]!!.updatedAt)

        val loaded = repo.routines(uid).single()
        assertEquals(listOf("Bench Press"), loaded.exercises.map { it.exerciseName })
        assertEquals(72.5, loaded.exercises[0].sets[1].weight, 0.0)
    }

    @Test fun workoutsFromSameRoutineDoNotCollideAndStoreSetDetails() = runBlocking {
        repo.saveMeasurement(uid, BodyMeasurement(weight = 80.0))
        val r = routine()
        repo.saveRoutine(uid, r)

        r.exercises[0].sets[0].apply { isCompleted = true; rpe = 8.5 }
        r.exercises[0].sets[1].apply { isCompleted = true; setType = SetType.FAILURE }
        r.exercises[1].sets[0].apply { weight = 0.0; repetitions = 0 }   // sin registrar → no se guarda
        repo.saveWorkout(uid, r, routineId = r.id)
        repo.saveWorkout(uid, r, routineId = r.id)

        val workouts = repo.workouts(uid)
        assertEquals(2, workouts.size)
        val w = workouts.first()
        assertEquals(listOf("Bench Press"), w.exercises.map { it.exerciseName })
        assertEquals(8.5, w.exercises[0].sets[0].rpe!!, 0.0)
        assertEquals(SetType.FAILURE, w.exercises[0].sets[1].setType)
        assertEquals(80.0, db.dao().workouts(uid).first().bodyweightKg!!, 0.0)
        assertEquals(4, db.dao().workoutSets(uid).size)

        val history = repo.exerciseHistory(uid, "Bench Press")
        assertEquals(2, history.size)
    }

    @Test fun deleteIsLogical() = runBlocking {
        val r = routine()
        repo.saveRoutine(uid, r)
        repo.deleteRoutine(r.id)
        assertTrue(repo.routines(uid).isEmpty())
        assertNotNull(db.dao().routine(r.id)!!.deletedAt)
    }

    @Test fun exportsJsonAndCsv() = runBlocking {
        val r = routine()
        r.exercises[0].sets.forEach { it.isCompleted = true }
        r.exercises[0].sets[0].rpe = 9.0
        repo.saveWorkout(uid, r.copy(name = "Push, heavy"), routineId = null)

        val json = File(context.cacheDir, "export.json")
        DataExporter.exportJson(context, repo, uid, Uri.fromFile(json))
        val root = JSONObject(json.readText())
        assertEquals(DataExporter.SCHEMA_VERSION, root.getInt("schemaVersion"))
        val set0 = root.getJSONArray("workouts").getJSONObject(0)
            .getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
        assertEquals(9.0, set0.getDouble("rpe"), 0.0)

        val csv = File(context.cacheDir, "export.csv")
        DataExporter.exportCsv(context, repo, uid, Uri.fromFile(csv))
        val lines = csv.readLines().filter { it.isNotBlank() }
        assertTrue(lines[0].startsWith("workout_id,started_at"))
        assertEquals(1 + 3, lines.size)                         // cabecera + 3 series
        assertTrue("el nombre con coma va entrecomillado", lines[1].contains("\"Push, heavy\""))
        assertTrue("e1RM calculado", lines[1].endsWith(",80.0"))  // 60 kg x 10 (Brzycki)
    }
}
