package com.gymflow.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gymflow.BodyMeasurement
import com.gymflow.CustomExercise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Prueba de extremo a extremo contra el servidor REAL (Ktor + PostgreSQL) en el PC:
 *   cd server && ./gradlew runDev      (el emulador lo ve en http://10.0.2.2:8080)
 * Si no está arrancado, el test se salta.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val base = "http://10.0.2.2:8080"
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    private val uid = System.getProperty("e2e.uid") ?: "e2e-${UUID.randomUUID()}"
    private lateinit var token: String
    private lateinit var a: Device
    private lateinit var b: Device

    @Before fun setUp() = runBlocking {
        val reachable = runCatching { get("/health").contains("ok") }.getOrDefault(false)
        assumeTrue("Servidor de desarrollo no arrancado en $base", reachable)
        token = get("/dev/token?uid=$uid")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        a = Device(ctx, HttpSyncApi(base, { token }))
        b = Device(ctx, HttpSyncApi(base, { token }))
    }

    @After fun tearDown() { if (::a.isInitialized) { a.db.close(); b.db.close() } }

    @Test fun fullRoundTripThroughRealServer() = runBlocking {
        // Móvil A: ejercicio propio, rutina, entreno y medida → sube
        a.repo.saveCustomExercise(uid, CustomExercise(name = "Bench Press", mainGroup = "Pecho"))
        val routine = sampleRoutine()
        a.repo.saveRoutine(uid, routine)
        routine.exercises.forEach { ex -> ex.sets.forEach { it.isCompleted = true } }
        routine.exercises[0].sets[0].rpe = 8.5
        a.repo.saveWorkout(uid, routine, routineId = routine.id)
        a.repo.saveMeasurement(uid, BodyMeasurement(weight = 79.4))
        val first = a.engine.sync(uid)
        assertTrue(first.pushed >= 10)
        assertEquals(0, a.repo.pendingChanges(uid))

        // Móvil B: lo recibe todo
        b.engine.sync(uid)
        assertEquals("Push", b.repo.routines(uid).single().name)
        assertEquals(8.5, b.repo.workouts(uid).single().exercises[0].sets[0].rpe!!, 0.0)
        assertEquals(79.4, b.repo.measurements(uid).single().weight, 0.0)

        // B edita, A recibe la edición
        Thread.sleep(5)
        b.repo.saveRoutine(uid, b.repo.routines(uid).single().copy(name = "Push (servidor real)"))
        b.engine.sync(uid)
        a.engine.sync(uid)
        assertEquals("Push (servidor real)", a.repo.routines(uid).single().name)

        // El panel web lee lo mismo: exportación con grupo muscular desde el servidor
        val export = syncJson.parseToJsonElement(get("/api/export", token)).jsonObject
        assertEquals(1, export["schemaVersion"]!!.jsonPrimitive.int)
        val ex0 = export["workouts"]!!.jsonArray.single().jsonObject["exercises"]!!.jsonArray[0].jsonObject
        assertEquals("Pecho", ex0["mainGroup"]!!.jsonPrimitive.content)

        // Vinculación del panel: código desde la app → token de solo lectura
        val code = HttpSyncApi(base, { token }).pairingCode().code
        val claim = post("/api/pairing/claim", """{"code":"$code","label":"e2e"}""")
        val panelToken = syncJson.parseToJsonElement(claim).jsonObject["token"]!!.jsonPrimitive.content
        assertTrue(get("/api/export", panelToken).contains("Push (servidor real)"))
    }

    private suspend fun get(path: String, bearer: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(req).execute().use { res -> check(res.isSuccessful) { "HTTP ${res.code} en $path" }; res.body!!.string() }
    }

    private suspend fun post(path: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).post(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res -> check(res.isSuccessful) { "HTTP ${res.code} en $path" }; res.body!!.string() }
    }
}
