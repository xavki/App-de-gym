package com.gymflow.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymflow.BodyMeasurement
import com.gymflow.data.GymDatabase
import com.gymflow.data.GymRepository
import com.gymflow.synccore.OperationIdentity
import com.gymflow.synccore.Content
import com.gymflow.synccore.ExportParser
import com.gymflow.synccore.ImportChoices
import com.gymflow.synccore.ImportExecutor
import com.gymflow.synccore.ImportPlanner
import com.gymflow.synccore.Rec
import com.gymflow.synccore.Tables
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adaptadores de Room del núcleo de sincronización (base en memoria, datos sintéticos). PENDIENTE DE EJECUCIÓN: requiere compilar
 * la app y un emulador desechable; no se han ejecutado en el entorno de desarrollo (sin SDK de Android).
 */
@RunWith(AndroidJUnit4::class)
class RoomStoresTest {
    private lateinit var db: GymDatabase
    private lateinit var repo: GymRepository
    private val uid = "uid-sintetico"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymDatabase::class.java).allowMainThreadQueries().build()
        repo = GymRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun store(user: String = uid) = RoomImportStore(db, user, "srv") { OperationIdentity.of(user, "srv") }
    private fun syncStore(user: String = uid) = RoomSyncStore(db, user, "srv") { OperationIdentity.of(user, "srv") }

    private fun measurement(id: String, user: String, weight: Double = 80.5) = BodyMeasurement(id = id, userId = user, weight = weight)

    @Test fun readingRecordsOnlySeesTheActiveUsersRows() = runBlocking {
        repo.saveMeasurement(uid, measurement("11111111-1111-1111-1111-111111111111", uid))
        repo.saveMeasurement("otro", measurement("22222222-2222-2222-2222-222222222222", "otro"))
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), store().transaction { all() }.map { it.id })
        assertEquals(listOf("22222222-2222-2222-2222-222222222222"), store("otro").transaction { all() }.map { it.id })
    }

    @Test fun recordsRoundTripThroughRoomWithoutChangingContentAndRepositoryEditsKeepTheServerRevision() = runBlocking {
        val id = "33333333-3333-3333-3333-333333333333"
        val rec = Rec(Tables.BODY_MEASUREMENTS, id, null, buildJsonObject {
            put("id", id); put("userId", uid); put("measuredAt", 1000L)
            for (f in listOf("weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm")) put(f, 80.5)
            put("createdAt", 1000L); put("updatedAt", 1000L); put("deletedAt", JsonNull)
        }, rev = 0, dirty = true)
        syncStore().transaction { put(rec.copy(rev = 7, dirty = false)) }
        val back = syncStore().transaction { records() }.single()
        assertTrue(Content.same(rec, back)); assertEquals(7L, back.rev); assertFalse(back.dirty)
        // editar desde el repositorio (que reconstruye la entidad) NO borra la revisión del servidor
        repo.saveMeasurement(uid, measurement(id, uid, weight = 81.0))
        assertEquals(7L, syncStore().transaction { records() }.single().rev)
        assertTrue(syncStore().transaction { records() }.single().dirty)
    }

    @Test fun importIsAtomicAndNeverOverwrites() = runBlocking {
        val st = store()
        val json = """{"schemaVersion":1,"userId":"$uid","bodyMeasurements":[
            {"id":"44444444-4444-4444-4444-444444444444","measuredAt":"2026-01-01T00:00:00Z","weightKg":80.0,"bodyFatPct":15.0,"chestCm":100.0,"waistCm":85.0,"hipsCm":95.0,"bicepCm":35.0,"thighCm":55.0}]}"""
        val plan = ImportPlanner.plan(ExportParser.parse(json.toByteArray(), uid), st.transaction { all() }, uid, ownerIdentity = st.boundIdentity)
        assertEquals(1, plan.newCount)
        ImportExecutor.apply(st, plan, ImportChoices())
        assertEquals(1, st.transaction { all() }.size)
        val again = ImportPlanner.plan(ExportParser.parse(json.toByteArray(), uid), st.transaction { all() }, uid, ownerIdentity = st.boundIdentity)
        assertEquals(0, again.newCount); assertEquals(1, again.identicalCount)
        val existing = st.transaction { all() }.first()
        assertTrue(runCatching { st.transaction { insert(existing) } }.isFailure)      // insertar algo que ya existe falla y se revierte
        assertEquals(1, st.transaction { all() }.size)
    }

    // ─── H1/H2/H5 (PENDIENTES DE EJECUCIÓN en emulador desechable) ───

    @Test fun anotherAccountsRowWithTheSameIdIsNeverReplaced() = runBlocking {
        val id = "55555555-5555-5555-5555-555555555555"
        repo.saveMeasurement("otro", measurement(id, "otro", weight = 70.0))
        val mine = Rec(Tables.BODY_MEASUREMENTS, id, null, buildJsonObject {
            put("id", id); put("userId", uid); put("measuredAt", 1000L)
            for (f in listOf("weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm")) put(f, 99.0)
            put("createdAt", 1000L); put("updatedAt", 1000L); put("deletedAt", JsonNull)
        }, rev = 0, dirty = true)
        assertTrue(runCatching { syncStore().transaction { put(mine) } }.isFailure)
        assertEquals(70.0, store("otro").transaction { all() }.single().data["weightKg"].let { (it as JsonPrimitive).content.toDouble() }, 0.0)
        assertTrue(store().transaction { all() }.isEmpty())
    }

    @Test fun identityChangeBeforeConfirmationWritesNothing() = runBlocking {
        var live: String? = OperationIdentity.of(uid, "srv")
        val st = RoomImportStore(db, uid, "srv") { live }
        val json = """{"schemaVersion":1,"userId":"$uid","bodyMeasurements":[
            {"id":"66666666-6666-6666-6666-666666666666","measuredAt":"2026-01-01T00:00:00Z","weightKg":80.0,"bodyFatPct":15.0,"chestCm":100.0,"waistCm":85.0,"hipsCm":95.0,"bicepCm":35.0,"thighCm":55.0}]}"""
        val plan = ImportPlanner.plan(ExportParser.parse(json.toByteArray(), uid), st.transaction { all() }, uid, ownerIdentity = st.boundIdentity)
        live = OperationIdentity.of("otra-cuenta", "srv")            // la sesión cambió tras la vista previa
        assertTrue(runCatching { ImportExecutor.apply(st, plan, ImportChoices()) }.isFailure)
        assertTrue(RoomImportStore(db, uid, "srv") { OperationIdentity.of(uid, "srv") }.transaction { all() }.isEmpty())
    }
}
