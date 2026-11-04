package com.gymflow.synccore

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * R3-04: esquemas Room 3 (histórico) y 4 (actual) incorporados al repositorio. Comprueba su integridad (SHA-256 de PROCEDENCIA_SCHEMAS),
 * su identidad y que MIGRATION_3_4 (SQL real, SQLite real) transforma una base creada con el DDL del esquema 3 en una cuyo DDL coincide con el 4.
 * Limitación: SQLite de escritorio, no Room/MigrationTestHelper (eso lo ejecuta Codex en Android).
 */
class RoomSchemaFilesTest {
    private val dir = File("../app/schemas/com.gymflow.data.GymDatabase")
    private fun db(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject
    /**
     * SHA-256 del contenido auténtico (LF). `.gitattributes` fija `eol=lf` para estos JSON, así que un checkout Windows los conserva en LF;
     * además, por robustez ante copias/zip que sí conviertan, se normaliza CRLF→LF antes de calcular: la suma esperada sigue siendo la
     * AUTÉNTICA del blob (no una suma CRLF local) y la identidad/estructura se comprueban igualmente.
     */
    private fun sha(v: Int): String {
        val raw = File(dir, "$v.json").readBytes()
        val lf = String(raw, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)
        return MessageDigest.getInstance("SHA-256").digest(lf).joinToString("") { "%02x".format(it) }
    }

    @Test fun `gitattributes fija LF para los esquemas JSON`() {
        val attrs = File("../.gitattributes").readText().replace("\r\n", "\n")
        assertTrue(attrs.lines().any { it.trim() == "app/schemas/**/*.json text eol=lf" })
    }

    @Test fun `el hash auténtico se obtiene tambien si el checkout fuese CRLF`() {
        val lf = File(dir, "3.json").readBytes()
        val crlf = String(lf, Charsets.ISO_8859_1).replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        fun h(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(String(b, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
        assertEquals(h(lf), h(crlf))
        assertTrue(!MessageDigest.getInstance("SHA-256").digest(crlf).contentEquals(MessageDigest.getInstance("SHA-256").digest(lf)))   // el bruto sí difiere
    }

    @Test fun `integridad e identidad de los esquemas 3 y 4`() {
        assertEquals("6c67f70849278a6de558fe0a674eb50bdb8416b6a8ad97afd27d10bb979b6296", sha(3))
        assertEquals("a7ed3adc43689fae3fea97756f37c46aacb2392e581e0f29b8bdf3cffebc864a", sha(4))
        assertEquals(3, db(3)["version"]!!.jsonPrimitive.content.toInt()); assertEquals("32a6e958badc613142b6b8920ec7611a", db(3)["identityHash"]!!.jsonPrimitive.content)
        assertEquals(4, db(4)["version"]!!.jsonPrimitive.content.toInt()); assertEquals("d7996e1a59d6e18ca49dae537a03cc80", db(4)["identityHash"]!!.jsonPrimitive.content)
        for (v in listOf(3, 4)) {
            assertEquals(14, db(v)["entities"]!!.jsonArray.size)
            assertTrue(db(v)["setupQueries"]!!.jsonArray.last().jsonPrimitive.content.contains(db(v)["identityHash"]!!.jsonPrimitive.content))
        }
    }

    @Test fun `la unica diferencia de modelo entre 3 y 4 es la clave de sync_revs`() {
        val e3 = db(3)["entities"]!!.jsonArray.associateBy { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        val e4 = db(4)["entities"]!!.jsonArray.associateBy { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        assertEquals(e3.keys, e4.keys)
        assertEquals(listOf("sync_revs"), e3.keys.filter { e3[it] != e4[it] })
        fun ddl(e: Map<String, kotlinx.serialization.json.JsonElement>, v: Int) =
            e.getValue("sync_revs").jsonObject["createSql"]!!.jsonPrimitive.content.replace("`\${TABLE_NAME}`", "`sync_revs`")
        assertEquals(RoomMigrationSql.SYNC_REVS_V3, ddl(e3, 3))      // la v3 distribuida: DDL de MIGRATION_2_3 == esquema 3
        assertEquals(RoomMigrationSql.SYNC_REVS_V4, ddl(e4, 4))      // y el DDL v4 == esquema 4
    }

    private fun create(v: Int): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (e in db(v)["entities"]!!.jsonArray.map { it.jsonObject }) {
            val t = e["tableName"]!!.jsonPrimitive.content
            c.createStatement().execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t))
            e["indices"]!!.jsonArray.forEach { c.createStatement().execute(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t)) }
        }
        return c
    }

    private fun shape(c: Connection): Map<String, String> = buildMap {
        c.createStatement().executeQuery("SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%'").use { rs ->
            while (rs.next()) put(rs.getString(1), rs.getString(2).replace(" IF NOT EXISTS", "").replace("\"", "`").replace(Regex("\\s+"), " "))
        }
    }

    @Test fun `una base creada con el DDL del esquema 3 migra a exactamente el DDL del esquema 4`() {
        val migrated = create(3)
        migrated.createStatement().execute("INSERT INTO routines (id,userId,name,createdAt,updatedAt,dirty) VALUES ('r1','uid-A','Push',1,1,0)")
        migrated.createStatement().execute("INSERT INTO sync_revs VALUES ('srv','routines','r1',4)")
        RoomMigrationSql.MIGRATE_3_4.forEach { migrated.createStatement().execute(it) }
        val fresh = create(4)
        assertEquals(shape(fresh), shape(migrated))          // mismas tablas, columnas, claves e índices que el esquema 4
        migrated.createStatement().executeQuery("SELECT userId, rev FROM sync_revs").use { rs -> assertTrue(rs.next()); assertEquals("uid-A", rs.getString(1)); assertEquals(4, rs.getInt(2)) }
        migrated.close(); fresh.close()
    }
}
