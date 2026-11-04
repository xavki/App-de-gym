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
    val rev: Long = 1,
)

class SyncRepository(private val db: Database) {

    /**
     * Aplica los cambios con control de versiones optimista (ver Models.kt). Todo en UNA transacción que bloquea la fila del
     * usuario: sus versiones se asignan y confirman en orden y un pull simultáneo nunca se salta cambios. Reenviar un lote
     * ya aplicado es idempotente (el contenido coincide ⇒ "unchanged"). Los grupos se aplican enteros o no se aplica ninguno.
     */
    fun push(uid: String, email: String?, changes: List<Change>): PushResponse = db.tx { c ->
        ensureUser(c, uid, email)
        var version = currentVersion(c, uid, forUpdate = true)
        val results = LinkedHashMap<Pair<String, String>, PushResult>()
        // unidad de trabajo: un grupo entero, o un cambio suelto
        val units: List<List<Change>> = changes.groupBy { it.group }.flatMap { (g, l) -> if (g == null) l.map { listOf(it) } else listOf(l) }
        for (unit in units) {
            val decided = unit.map { ch -> val existing = load(c, uid, ch.table, ch.id); Triple(ch, existing, decide(existing, ch)) }
            val blockedByGroup = unit.size > 1 && decided.any { it.third is Decision.Conflict }
            for ((ch, existing, d) in decided) {
                val k = ch.table to ch.id
                results[k] = when {
                    d is Decision.Conflict -> PushResult(ch.table, ch.id, STATUS_CONFLICT, existing!!.rev, existing.toPulled())
                    blockedByGroup -> PushResult(ch.table, ch.id, STATUS_BLOCKED)
                    d is Decision.Unchanged -> PushResult(ch.table, ch.id, STATUS_UNCHANGED, d.rev)
                    d is Decision.Accept -> {
                        version++
                        write(c, uid, ch, d.rev, version)
                        PushResult(ch.table, ch.id, STATUS_ACCEPTED, d.rev)
                    }
                    else -> error("decisión desconocida")
                }
            }
        }
        c.prepareStatement("UPDATE users SET sync_version = ? WHERE id = ?").use { st ->
            st.setLong(1, version); st.setString(2, uid); st.executeUpdate()
        }
        val ordered = changes.map { results.getValue(it.table to it.id) }
        PushResponse(
            accepted = ordered.count { it.status == STATUS_ACCEPTED || it.status == STATUS_UNCHANGED },
            ignored = ordered.count { it.status == STATUS_CONFLICT || it.status == STATUS_BLOCKED },
            results = ordered,
        )
    }

    private sealed class Decision {
        data class Accept(val rev: Long) : Decision()
        data class Unchanged(val rev: Long) : Decision()
        data object Conflict : Decision()
    }

    private fun decide(existing: StoredRecord?, ch: Change): Decision = when {
        existing == null -> Decision.Accept(1)
        ContentEquality.same(existing.data, existing.deletedAt != null, ch.data, ch.deletedAt != null) -> Decision.Unchanged(existing.rev)
        ch.baseRev == existing.rev -> Decision.Accept(existing.rev + 1)
        else -> Decision.Conflict
    }

    private fun load(c: Connection, uid: String, table: String, id: String): StoredRecord? =
        c.prepareStatement(
            "SELECT table_name, id, updated_at, deleted_at, version, data::text, rev FROM records WHERE user_id = ? AND table_name = ? AND id = ? FOR UPDATE"
        ).use { st ->
            st.setString(1, uid); st.setString(2, table); st.setString(3, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    private fun write(c: Connection, uid: String, ch: Change, rev: Long, version: Long) {
        c.prepareStatement(UPSERT).use { st ->
            st.setString(1, uid); st.setString(2, ch.table); st.setString(3, ch.id)
            st.setLong(4, ch.updatedAt)
            if (ch.deletedAt != null) st.setLong(5, ch.deletedAt) else st.setNull(5, java.sql.Types.BIGINT)
            st.setLong(6, version); st.setLong(7, rev); st.setString(8, ch.data.toString())
            st.executeUpdate()
        }
    }

    private fun StoredRecord.toPulled() = PulledChange(table, id, updatedAt, deletedAt, data, version, rev)

    fun pull(uid: String, since: Long, limit: Int): PullResponse = db.tx { c ->
        val current = currentVersion(c, uid, forUpdate = false)
        val reset = since > current
        val from = if (reset) 0L else since
        val rows = c.prepareStatement(
            """
            SELECT table_name, id, updated_at, deleted_at, version, data::text, rev
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
            changes = page.map { it.toPulled() },
            cursor = page.lastOrNull()?.version ?: from,
            hasMore = rows.size > limit,
            reset = reset,
        )
    }

    /** Todos los registros del usuario (incluidos los borrados), para exportar. */
    fun allRecords(uid: String): List<StoredRecord> = db.tx { c ->
        c.prepareStatement(
            "SELECT table_name, id, updated_at, deleted_at, version, data::text, rev FROM records WHERE user_id = ?"
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
        rev = getLong(7),
    )

    companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_UNCHANGED = "unchanged"
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_BLOCKED = "blocked"

        private val UPSERT = """
            INSERT INTO records (user_id, table_name, id, updated_at, deleted_at, version, rev, data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (user_id, table_name, id) DO UPDATE
               SET updated_at = EXCLUDED.updated_at,
                   deleted_at = EXCLUDED.deleted_at,
                   version    = EXCLUDED.version,
                   rev        = EXCLUDED.rev,
                   data       = EXCLUDED.data
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
