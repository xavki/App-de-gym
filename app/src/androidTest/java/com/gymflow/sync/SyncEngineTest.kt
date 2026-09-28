package com.gymflow.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymflow.BodyMeasurement
import com.gymflow.ExerciseSet
import com.gymflow.WorkoutExercise
import com.gymflow.WorkoutSession
import com.gymflow.data.GymDatabase
import com.gymflow.data.GymRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Servidor en memoria con las mismas reglas que el real (last-write-wins, cursor por versión). */
class FakeServer : SyncApi {
    private val records = linkedMapOf<Pair<String, String>, PulledChange>()
    private var version = 0L
    var pushes = 0

    override suspend fun push(changes: List<Change>): PushResponse {
        pushes++
        var accepted = 0
        for (c in changes) {
            val key = c.table to c.id
            val existing = records[key]
            if (existing == null || c.updatedAt > existing.updatedAt) {
                records[key] = PulledChange(c.table, c.id, c.updatedAt, c.deletedAt, c.data, ++version)
                accepted++
            }
        }
        return PushResponse(accepted, changes.size - accepted)
    }

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        val all = records.values.filter { it.version > since }.sortedBy { it.version }
        val page = all.take(limit)
        return PullResponse(page, page.lastOrNull()?.version ?: since, all.size > limit)
    }

    override suspend fun pairingCode() = PairingCodeResponse("TEST-CODE", 0)
    override suspend fun revokePanels() = RevokeResponse(0)
}

class MemoryCursors : CursorStore {
    private val map = mutableMapOf<String, Long>()
    override fun get(uid: String) = map[uid] ?: 0
    override fun set(uid: String, cursor: Long) { map[uid] = cursor }
}

/** Un "móvil": su propia base de datos y su motor de sincronización. */
class Device(context: Context, api: SyncApi) {
    val db: GymDatabase = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
    val repo = GymRepository(db)
    val engine = SyncEngine(db, api, MemoryCursors())
}

fun sampleRoutine(name: String = "Push") = WorkoutSession(
    name = name,
    exercises = mutableListOf(
        WorkoutExercise(exerciseName = "Bench Press", sets = mutableListOf(ExerciseSet(weight = 60.0, repetitions = 10))),
        WorkoutExercise(exerciseName = "Overhead Press", sets = mutableListOf(ExerciseSet(weight = 40.0, repetitions = 8)))
    )
)

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private val uid = "user-1"
    private lateinit var server: FakeServer
    private lateinit var a: Device
    private lateinit var b: Device

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        server = FakeServer()
        a = Device(ctx, server)
        b = Device(ctx, server)
    }

    @After fun tearDown() { a.db.close(); b.db.close() }

    @Test fun dataCreatedOnOnePhoneAppearsOnTheOther() = runBlocking {
        val routine = sampleRoutine()
        a.repo.saveRoutine(uid, routine)
        routine.exercises[0].sets[0].isCompleted = true
        routine.exercises[0].sets[0].rpe = 8.0
        a.repo.saveWorkout(uid, routine, routineId = routine.id)
        a.repo.saveMeasurement(uid, BodyMeasurement(weight = 81.5))

        val pushed = a.engine.sync(uid).pushed
        assertTrue(pushed >= 9)
        assertEquals(0, a.repo.pendingChanges(uid))

        val result = b.engine.sync(uid)
        assertEquals(pushed, result.pulled)
        assertEquals(listOf("Bench Press", "Overhead Press"), b.repo.routines(uid).single().exercises.map { it.exerciseName })
        val workout = b.repo.workouts(uid).single()
        assertEquals(8.0, workout.exercises[0].sets[0].rpe!!, 0.0)
        assertEquals(81.5, b.repo.measurements(uid).single().weight, 0.0)
        // Lo recibido no queda pendiente de subir
        assertEquals(0, b.repo.pendingChanges(uid))
    }

    @Test fun editsAndDeletionsPropagate() = runBlocking {
        val routine = sampleRoutine()
        a.repo.saveRoutine(uid, routine)
        val m = BodyMeasurement(weight = 80.0)
        a.repo.saveMeasurement(uid, m)
        a.engine.sync(uid); b.engine.sync(uid)

        // En B: renombrar la rutina, quitar un ejercicio y borrar la medida
        val onB = b.repo.routines(uid).single()
        Thread.sleep(5)
        b.repo.saveRoutine(uid, onB.copy(name = "Push fuerte", exercises = mutableListOf(onB.exercises[0])))
        b.repo.deleteMeasurement(m.id)
        b.engine.sync(uid)

        a.engine.sync(uid)
        val onA = a.repo.routines(uid).single()
        assertEquals("Push fuerte", onA.name)
        assertEquals(listOf("Bench Press"), onA.exercises.map { it.exerciseName })
        assertTrue(a.repo.measurements(uid).isEmpty())
    }

    @Test fun lastWriteWinsOnConflicts() = runBlocking {
        val routine = sampleRoutine()
        a.repo.saveRoutine(uid, routine)
        a.engine.sync(uid); b.engine.sync(uid)

        // Los dos editan sin conexión; B lo hace después
        a.repo.saveRoutine(uid, routine.copy(name = "Nombre de A"))
        Thread.sleep(5)
        b.repo.saveRoutine(uid, b.repo.routines(uid).single().copy(name = "Nombre de B"))

        a.engine.sync(uid)   // A sube primero
        b.engine.sync(uid)   // B sube su versión, más nueva
        a.engine.sync(uid)   // A recibe la de B
        assertEquals("Nombre de B", a.repo.routines(uid).single().name)
        assertEquals("Nombre de B", b.repo.routines(uid).single().name)
    }

    @Test fun olderRemoteVersionDoesNotOverwriteNewerLocalEdit() = runBlocking {
        val routine = sampleRoutine()
        a.repo.saveRoutine(uid, routine)
        a.engine.sync(uid)
        Thread.sleep(5)
        a.repo.saveRoutine(uid, routine.copy(name = "Editada sin conexión"))
        // Otro dispositivo había subido una versión más antigua del mismo registro
        b.engine.sync(uid)
        a.engine.sync(uid)
        assertEquals("Editada sin conexión", a.repo.routines(uid).single().name)
    }

    @Test fun nothingPendingMeansNoPush() = runBlocking {
        a.repo.saveRoutine(uid, sampleRoutine())
        a.engine.sync(uid)
        val before = server.pushes
        a.engine.sync(uid)
        assertEquals(before, server.pushes)
    }

    @Test fun importedRowsNeverBeatRealEdits() = runBlocking {
        // A tiene la rutina editada; B la "importa" de Firestore con la fecha antigua
        val routine = sampleRoutine("Versión real")
        a.repo.saveRoutine(uid, routine)
        a.engine.sync(uid)
        b.repo.saveRoutine(uid, routine.copy(name = "Versión vieja de Firestore"), at = GymRepository.IMPORTED_AT)
        b.engine.sync(uid)
        assertEquals("Versión real", b.repo.routines(uid).single().name)
        a.engine.sync(uid)
        assertEquals("Versión real", a.repo.routines(uid).single().name)
    }
}
