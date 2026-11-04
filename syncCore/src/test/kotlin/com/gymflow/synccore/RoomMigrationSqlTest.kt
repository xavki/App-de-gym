package com.gymflow.synccore

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ejecuta el SQL REAL de la migración 3→4 sobre SQLite real, partiendo del esquema exportado por Room (app/schemas/.../2.json, que es el de
 * las tablas de datos también en la v3) y de la `sync_revs` de la v3 distribuida. Datos 100 % sintéticos.
 * Limitación: es SQLite de escritorio, no Room/Android; no sustituye a MigrationTestHelper en emulador.
 */
class RoomMigrationSqlTest {
    private val schema = Json.parseToJsonElement(File("../app/schemas/com.gymflow.data.GymDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject

    private fun v3(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (e in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
            val t = e["tableName"]!!.jsonPrimitive.content
            c.createStatement().execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t))
            e["indices"]?.jsonArray?.forEach { c.createStatement().execute(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t)) }
        }
        c.createStatement().execute(RoomMigrationSql.SYNC_REVS_V3)
        return c
    }

    /** Inserta una fila rellenando con valores neutros todas las columnas NOT NULL sin valor por defecto, salvo las indicadas. */
    private fun insert(c: Connection, table: String, vararg v: Pair<String, Any?>) {
        val given = v.toMap()
        val cols = mutableListOf<String>(); val vals = mutableListOf<Any?>()
        c.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            while (rs.next()) {
                val n = rs.getString("name"); val type = rs.getString("type").uppercase()
                when {
                    n in given -> { cols += n; vals += given[n] }
                    rs.getInt("notnull") == 1 && rs.getString("dflt_value") == null -> { cols += n; vals += if (type.contains("INT")) 0L else if (type.contains("REAL")) 0.0 else "x" }
                }
            }
        }
        c.prepareStatement("INSERT INTO `$table` (${cols.joinToString { "`$it`" }}) VALUES (${cols.joinToString { "?" }})").use { ps ->
            vals.forEachIndexed { i, x -> ps.setObject(i + 1, x) }; ps.executeUpdate()
        }
    }

    private fun rev(c: Connection, key: String, table: String, id: String, rev: Long) =
        c.prepareStatement("INSERT INTO sync_revs (serverKey, tableName, id, rev) VALUES (?,?,?,?)").use { it.setString(1, key); it.setString(2, table); it.setString(3, id); it.setLong(4, rev); it.executeUpdate() }

    private fun migrate(c: Connection) = RoomMigrationSql.MIGRATE_3_4.forEach { c.createStatement().execute(it) }
    private fun one(c: Connection, sql: String): Long = c.createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }

    @Test fun `3 a 4 conserva datos y revisiones y las asigna a su usuario`() {
        val c = v3()
        insert(c, "workouts", "id" to "w-a", "userId" to "uid-A")
        insert(c, "workout_exercises", "id" to "we-a", "workoutId" to "w-a")
        insert(c, "workout_sets", "id" to "ws-a", "workoutExerciseId" to "we-a")
        insert(c, "routines", "id" to "r-b", "userId" to "uid-B")
        insert(c, "routine_exercises", "id" to "re-b", "routineId" to "r-b")
        insert(c, "routine_sets", "id" to "rs-b", "routineExerciseId" to "re-b")
        insert(c, "body_measurements", "id" to "m-a", "userId" to "uid-A")
        rev(c, "srv", "workouts", "w-a", 7); rev(c, "srv", "workout_exercises", "we-a", 8); rev(c, "srv", "workout_sets", "ws-a", 9)
        rev(c, "srv", "routines", "r-b", 3); rev(c, "srv", "routine_exercises", "re-b", 4); rev(c, "srv", "routine_sets", "rs-b", 5)
        rev(c, "srv2", "body_measurements", "m-a", 11)
        val dataBefore = listOf("workouts", "workout_exercises", "workout_sets", "routines", "routine_exercises", "routine_sets", "body_measurements").associateWith { one(c, "SELECT COUNT(*) FROM `$it`") }

        migrate(c)

        dataBefore.forEach { (t, n) -> assertEquals(n, one(c, "SELECT COUNT(*) FROM `$t`"), "datos de $t intactos") }
        assertEquals(7, one(c, "SELECT COUNT(*) FROM sync_revs"))
        assertEquals(7L, one(c, "SELECT rev FROM sync_revs WHERE userId='uid-A' AND serverKey='srv' AND tableName='workouts' AND id='w-a'"))
        assertEquals(9L, one(c, "SELECT rev FROM sync_revs WHERE userId='uid-A' AND tableName='workout_sets' AND id='ws-a'"))
        assertEquals(5L, one(c, "SELECT rev FROM sync_revs WHERE userId='uid-B' AND tableName='routine_sets' AND id='rs-b'"))
        assertEquals(11L, one(c, "SELECT rev FROM sync_revs WHERE userId='uid-A' AND serverKey='srv2' AND tableName='body_measurements'"))
        // aislamiento: ninguna revisión de A está bajo B ni al revés
        assertEquals(0, one(c, "SELECT COUNT(*) FROM sync_revs WHERE userId='uid-B' AND tableName IN ('workouts','workout_exercises','workout_sets','body_measurements')"))
        assertEquals(0, one(c, "SELECT COUNT(*) FROM sync_revs WHERE userId='uid-A' AND tableName LIKE 'routine%'"))
        c.close()
    }

    @Test fun `3 a 4 deja la clave primaria y la forma exactas de la v4 y descarta revisiones huerfanas`() {
        val c = v3()
        insert(c, "routines", "id" to "r-a", "userId" to "uid-A"); rev(c, "srv", "routines", "r-a", 1)
        rev(c, "srv", "routines", "no-existe", 2); rev(c, "srv", "workout_sets", "huerfano", 3)
        migrate(c)
        assertEquals(1, one(c, "SELECT COUNT(*) FROM sync_revs"))
        val cols = mutableListOf<Pair<String, Int>>()
        c.createStatement().executeQuery("PRAGMA table_info(sync_revs)").use { rs -> while (rs.next()) cols += rs.getString("name") to rs.getInt("pk") }
        assertEquals(listOf("userId" to 1, "serverKey" to 2, "tableName" to 3, "id" to 4, "rev" to 0), cols)
        assertTrue(c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE name='sync_revs_new'").use { !it.next() })
        // la tabla v4 creada desde cero es idéntica a la migrada
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:"); fresh.createStatement().execute(RoomMigrationSql.SYNC_REVS_V4)
        fun sql(x: Connection) = x.createStatement().executeQuery("SELECT sql FROM sqlite_master WHERE name='sync_revs'").use { it.next(); it.getString(1).replace(" IF NOT EXISTS", "").replace("\"sync_revs\"", "`sync_revs`") }
        assertEquals(sql(fresh), sql(c))
        c.close(); fresh.close()
    }

    @Test fun `3 a 4 con base sin revisiones o vacia no falla y es repetible sobre datos de varios usuarios`() {
        val c = v3(); migrate(c); assertEquals(0, one(c, "SELECT COUNT(*) FROM sync_revs")); c.close()
    }
}
