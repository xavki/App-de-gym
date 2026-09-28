package com.gymflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection

/** Un registro tal como está guardado en `records`. */
data class StoredRecord(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val version: Long,
    val data: JsonObject,
)

class SyncRepository(private val db: Database) {

    /**
     * Aplica los cambios con last-write-wins: un registro solo se sobrescribe si el
     * que llega es más reciente. Todo en una transacción que bloquea la fila del
     * usuario, así sus versiones se asignan y confirman en orden y un pull
     * simultáneo nunca se salta cambios.
     */
    fun push(uid: String, email: String?, changes: List<Change>): PushResponse = db.tx { c ->
        ensureUser(c, uid, email)
        val base = currentVersion(c, uid, forUpdate = true)
        var accepted = 0
        c.prepareStatement(UPSERT).use { st ->
            changes.forEachIndexed { i, ch ->
                st.setString(1, uid)
                st.setString(2, ch.table)
                st.setString(3, ch.id)
                st.setLong(4, ch.updatedAt)
                if (ch.deletedAt != null) st.setLong(5, ch.deletedAt) else st.setNull(5, java.sql.Types.BIGINT)
                st.setLong(6, base + i + 1) // los huecos (cambios ignorados) no importan
                st.setString(7, ch.data.toString())
                st.addBatch()
            }
            accepted = st.executeBatch().count { it > 0 }
        }
        c.prepareStatement("UPDATE users SET sync_version = ? WHERE id = ?").use { st ->
            st.setLong(1, base + changes.size)
            st.setString(2, uid)
            st.executeUpdate()
        }
        PushResponse(accepted = accepted, ignored = changes.size - accepted)
    }

    fun pull(uid: String, since: Long, limit: Int): PullResponse = db.tx { c ->
        val current = currentVersion(c, uid, forUpdate = false)
        val reset = since > current
        val from = if (reset) 0L else since
        val rows = c.prepareStatement(
            """
            SELECT table_name, id, updated_at, deleted_at, version, data::text
              FROM records
             WHERE user_id = ? AND version > ?
             ORDER BY version
             LIMIT ?
            """.trimIndent()
        ).use { st ->
            st.setString(1, uid)
            st.setLong(2, from)
            st.setInt(3, limit + 1)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
        }
        val page = rows.take(limit)
        PullResponse(
            changes = page.map { PulledChange(it.table, it.id, it.updatedAt, it.deletedAt, it.data, it.version) },
            cursor = page.lastOrNull()?.version ?: from,
            hasMore = rows.size > limit,
            reset = reset,
        )
    }

    /** Todos los registros del usuario (incluidos los borrados), para exportar. */
    fun allRecords(uid: String): List<StoredRecord> = db.tx { c ->
        c.prepareStatement(
            "SELECT table_name, id, updated_at, deleted_at, version, data::text FROM records WHERE user_id = ?"
        ).use { st ->
            st.setString(1, uid)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
        }
    }

    fun me(uid: String, email: String?): MeResponse = db.tx { c ->
        ensureUser(c, uid, email)
        val records = c.prepareStatement("SELECT count(*) FROM records WHERE user_id = ?").use { st ->
            st.setString(1, uid)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        MeResponse(uid, email, records, currentVersion(c, uid, forUpdate = false))
    }

    private fun currentVersion(c: Connection, uid: String, forUpdate: Boolean): Long =
        c.prepareStatement("SELECT sync_version FROM users WHERE id = ?" + if (forUpdate) " FOR UPDATE" else "").use { st ->
            st.setString(1, uid)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    private fun java.sql.ResultSet.toRecord() = StoredRecord(
        table = getString(1),
        id = getString(2),
        updatedAt = getLong(3),
        deletedAt = getLong(4).takeUnless { wasNull() },
        version = getLong(5),
        data = Json.parseToJsonElement(getString(6)).jsonObject,
    )

    companion object {
        private val UPSERT = """
            INSERT INTO records (user_id, table_name, id, updated_at, deleted_at, version, data)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (user_id, table_name, id) DO UPDATE
               SET updated_at = EXCLUDED.updated_at,
                   deleted_at = EXCLUDED.deleted_at,
                   version    = EXCLUDED.version,
                   data       = EXCLUDED.data
             WHERE EXCLUDED.updated_at > records.updated_at
        """.trimIndent()

        fun ensureUser(c: Connection, uid: String, email: String?) {
            c.prepareStatement(
                """
                INSERT INTO users (id, email, last_seen_at) VALUES (?, ?, now())
                ON CONFLICT (id) DO UPDATE SET last_seen_at = now(), email = COALESCE(EXCLUDED.email, users.email)
                """.trimIndent()
            ).use { st ->
                st.setString(1, uid)
                st.setString(2, email)
                st.executeUpdate()
            }
        }
    }
}
