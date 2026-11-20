package com.gymflow.synccore

import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExportParserTest {
    private fun file(vararg groups: List<Rec>) = exportJson(groups.flatMap { it }).toByteArray()
    private fun parse(text: String, limits: ImportLimits = ImportLimits()) = ExportParser.parse(text.toByteArray(), UID, limits)
    private fun code(text: String, limits: ImportLimits = ImportLimits()) = assertFailsWith<ImportFileException> { parse(text, limits) }.code

    @Test fun `un JSON valido se lee completo con todos los tipos`() {
        val recs = workoutRecs(1) + routineRecs(1) + listOf(measurementRec(1), customExerciseRec(1), achievementRec("first_workout"))
        val p = ExportParser.parse(file(recs), UID)
        assertEquals(1, p.schemaVersion); assertEquals(UID, p.fileUserId)
        assertEquals(5, p.aggregates.size); assertTrue(p.invalid.isEmpty())
        val w = p.aggregates.first { it.kind == Kind.WORKOUT }
        assertEquals(1 + 2 + 6, w.records.size)                                          // entreno + 2 ejercicios + 6 series
        assertEquals(UID, w.root.str("userId"))                                          // la identidad es la sesión activa
        assertTrue(w.records.all { it.dirty && it.rev == 0L })
        val r = p.aggregates.first { it.kind == Kind.ROUTINE }
        assertEquals(1 + 2 + 4, r.records.size)
        assertEquals(stableUuid("${uuid("re1-0")}:set:0"), r.records.first { it.table == Tables.ROUTINE_SETS }.id)   // ids de series derivados de forma estable
    }

    @Test fun `JSON vacio o sin esquema se rechaza`() {
        assertEquals(ImportError.NOT_JSON, code(""))
        assertEquals(ImportError.NOT_OBJECT, code("[]"))
        assertEquals(ImportError.MISSING_SCHEMA, code("{}"))
        assertEquals(ImportError.MISSING_SCHEMA, code("""{"schemaVersion":"1"}"""))
    }

    @Test fun `JSON corrupto o truncado se rechaza`() {
        assertEquals(ImportError.NOT_JSON, code("""{"schemaVersion":1,"workouts":[{"id":"""))
        assertEquals(ImportError.NOT_JSON, code("esto no es json"))
        val ok = exportJson(workoutRecs(1))
        assertEquals(ImportError.NOT_JSON, code(ok.substring(0, ok.length / 2)))
    }

    @Test fun `version de esquema incompatible`() {
        assertEquals(ImportError.UNSUPPORTED_SCHEMA, code(exportJson(workoutRecs(1), schema = 2)))
        assertEquals(ImportError.UNSUPPORTED_SCHEMA, code(exportJson(workoutRecs(1), schema = 0)))
    }

    @Test fun `archivo demasiado grande o demasiado anidado`() {
        assertEquals(ImportError.TOO_LARGE, assertFailsWith<ImportFileException> { ExportParser.parse(ByteArray(2000) { ' '.code.toByte() }, UID, ImportLimits(maxBytes = 1000)) }.code)
        val deep = "[".repeat(100) + "]".repeat(100)
        assertEquals(ImportError.TOO_DEEP, code("""{"schemaVersion":1,"x":$deep}"""))
        assertEquals(ImportError.TOO_MANY, code(exportJson(workoutRecs(1) + workoutRecs(2)), ImportLimits(maxWorkouts = 1)))
    }

    @Test fun `valores invalidos invalidan solo el registro afectado`() {
        val good = workoutRecs(1)
        val bad = workoutRecs(2).map { if (it.table == Tables.WORKOUT_SETS) it.withNum("reps", -5) else it }
        val p = ExportParser.parse(file(good, bad), UID)
        assertEquals(1, p.aggregates.size); assertEquals(1, p.invalid.size)
        assertTrue(p.invalid.single().reason.contains("reps"))
    }

    @Test fun `fechas, ids, tipos y rangos incorrectos`() {
        fun invalidReason(mut: (String) -> String): String {
            val p = ExportParser.parse(mut(exportJson(workoutRecs(1))).toByteArray(), UID)
            assertTrue(p.aggregates.isEmpty(), "debía invalidarse"); return p.invalid.single().reason
        }
        assertTrue(invalidReason { it.replace("\"startedAt\":\"2025", "\"startedAt\":\"25") }.contains("fecha"))
        assertTrue(invalidReason { it.replace(uuid("w1"), "no-es-uuid") }.contains("UUID"))
        assertTrue(invalidReason { it.replace("\"durationSeconds\":3001", "\"durationSeconds\":\"3001\"") }.contains("durationSeconds"))
        assertTrue(invalidReason { it.replace("\"weightKg\":61.0", "\"weightKg\":99999.0") }.contains("fuera de rango"))
        assertTrue(invalidReason { it.replace("\"completed\":true", "\"completed\":\"yes\"") }.contains("completed"))
    }

    @Test fun `ids duplicados dentro del archivo invalidan todos los implicados`() {
        val base = Json.parseToJsonElement(exportJson(workoutRecs(1) + workoutRecs(3))).jsonObject
        val ws = base["workouts"]!!.jsonArray
        val dup = JsonObject(ws[0].jsonObject + ("name" to JsonPrimitive("otro nombre")))      // mismo id, distinto contenido
        val text = JsonObject(base + ("workouts" to JsonArray(ws + dup))).toString()
        val p = parse(text)
        assertEquals(1, p.aggregates.size)                                  // solo el entreno 3
        assertEquals(2, p.invalid.size); assertTrue(p.invalid.all { it.reason.contains("duplicado") })
    }

    @Test fun `ids repetidos dentro de un mismo registro y posiciones repetidas`() {
        val w = workoutRecs(1)
        val ex = w.first { it.table == Tables.WORKOUT_EXERCISES }
        val repeated = w.map { if (it.table == Tables.WORKOUT_EXERCISES && it.id != ex.id) it.copy(id = ex.id).withStr("id", ex.id) else it }
        assertEquals(1, ExportParser.parse(file(repeated), UID).invalid.size)
        val samePos = w.map { if (it.table == Tables.WORKOUT_EXERCISES) it.withNum("position", 0) else it }
        assertEquals(1, ExportParser.parse(file(samePos), UID).invalid.size)
    }

    @Test fun `referencias a rutinas fuera del archivo se avisan sin bloquear`() {
        val w = workoutRecs(1).map { if (it.table == Tables.WORKOUTS) it.withStr("routineId", uuid("rutina-que-no-esta")) else it }
        val p = ExportParser.parse(file(w), UID)
        assertEquals(1, p.aggregates.size); assertTrue(p.warnings.any { it.contains("rutina") })
    }

    @Test fun `el userId del archivo es solo informativo`() {
        val p = ExportParser.parse(exportJson(workoutRecs(1), uid = "otra-cuenta").toByteArray(), UID)
        assertEquals("otra-cuenta", p.fileUserId)
        assertTrue(p.aggregates.single().records.all { it.str("userId") == null || it.str("userId") == UID })   // nunca se hereda el id del archivo
    }

    @Test fun `un archivo mal formado nunca produce excepciones inesperadas`() {
        val samples = listOf("null", "true", "123", "\"x\"", """{"schemaVersion":1,"workouts":5}""", """{"schemaVersion":1,"workouts":[1,null,"x",[]]}""",
            """{"schemaVersion":1,"achievements":[{"key":"../../x","unlockedAt":"2026-01-01T00:00:00Z"}]}""", "\u0000\u0001")
        for (s in samples) runCatching { parse(s) }.let { r -> r.exceptionOrNull()?.let { assertTrue(it is ImportFileException, "excepción inesperada: $it") } }
        assertNotNull(parse("""{"schemaVersion":1,"workouts":[1,null,"x",[]]}""").invalid.firstOrNull())
    }
}
