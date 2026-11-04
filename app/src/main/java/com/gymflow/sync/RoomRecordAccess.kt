package com.gymflow.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gymflow.synccore.Key
import com.gymflow.synccore.Owner
import com.gymflow.synccore.OwnerGuard
import com.gymflow.synccore.OwnerLookup
import com.gymflow.synccore.SqlValue
import com.gymflow.synccore.SqlValues
import com.gymflow.synccore.Rec
import com.gymflow.synccore.Tables
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Acceso GENÉRICO a las filas de usuario de Room como [Rec] (JSON con los nombres de columna = nombres de campo de las entidades).
 * Se usa SIEMPRE dentro de una transacción de Room (db.withTransaction). Solo ve las filas del usuario [uid]:
 * las tablas hijas se alcanzan por su padre, así que nunca se mezclan cuentas.
 */
internal class RoomRecordAccess(
    private val sdb: SupportSQLiteDatabase,
    private val uid: String,
    private val serverKey: String,
) {
    private class Col(val name: String, val type: String)

    private val columnsCache = HashMap<String, List<Col>>()

    private fun columns(table: String): List<Col> = columnsCache.getOrPut(table) {
        sdb.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name"); val type = c.getColumnIndexOrThrow("type")
            buildList { while (c.moveToNext()) add(Col(c.getString(name), c.getString(type).uppercase())) }
        }
    }

    /** SELECT de las filas del usuario de cada tabla. Los parámetros siempre son [uid]. */
    private fun scopeSql(table: String): String = when (table) {
        Tables.EXERCISES, Tables.ROUTINES, Tables.WORKOUTS, Tables.BODY_MEASUREMENTS, Tables.SCHEDULES, Tables.ACHIEVEMENTS ->
            "SELECT t.* FROM `$table` t WHERE t.userId = ?"
        Tables.ROUTINE_EXERCISES ->
            "SELECT t.* FROM routine_exercises t JOIN routines p ON p.id = t.routineId WHERE p.userId = ?"
        Tables.ROUTINE_SETS ->
            "SELECT t.* FROM routine_sets t JOIN routine_exercises e ON e.id = t.routineExerciseId JOIN routines p ON p.id = e.routineId WHERE p.userId = ?"
        Tables.WORKOUT_EXERCISES ->
            "SELECT t.* FROM workout_exercises t JOIN workouts p ON p.id = t.workoutId WHERE p.userId = ?"
        Tables.WORKOUT_SETS ->
            "SELECT t.* FROM workout_sets t JOIN workout_exercises e ON e.id = t.workoutExerciseId JOIN workouts p ON p.id = e.workoutId WHERE p.userId = ?"
        else -> error("Tabla no sincronizable: $table")
    }

    private fun revs(): Map<Key, Long> = sdb.query("SELECT tableName, id, rev FROM sync_revs WHERE userId = ? AND serverKey = ?", arrayOf<Any>(uid, serverKey)).use { c ->
        buildMap { while (c.moveToNext()) put(Key(c.getString(0), c.getString(1)), c.getLong(2)) }
    }

    fun records(): List<Rec> {
        val revs = revs()
        val out = ArrayList<Rec>()
        for (table in Tables.ALL) {
            sdb.query(scopeSql(table), arrayOf<Any>(uid)).use { c ->
                while (c.moveToNext()) {
                    val rec = toRec(table, c, revs)
                    out += rec
                }
            }
        }
        return out
    }

    /** ¿Existe ya una fila con este id en la tabla (de CUALQUIER cuenta)? */
    fun exists(table: String, id: String): Boolean =
        sdb.query("SELECT 1 FROM `$table` WHERE id = ?", arrayOf<Any>(id)).use { it.moveToFirst() }

    /** Propietario REAL de la fila con ese id, resuelto en SQL por la cadena de padres (nunca por el contenido recibido). */
    private val ownerSql: Map<String, String> = mapOf(
        Tables.EXERCISES to "SELECT userId FROM exercises WHERE id = ?",
        Tables.ROUTINES to "SELECT userId FROM routines WHERE id = ?",
        Tables.WORKOUTS to "SELECT userId FROM workouts WHERE id = ?",
        Tables.BODY_MEASUREMENTS to "SELECT userId FROM body_measurements WHERE id = ?",
        Tables.SCHEDULES to "SELECT userId FROM schedules WHERE id = ?",
        Tables.ACHIEVEMENTS to "SELECT userId FROM achievements WHERE id = ?",
        Tables.ROUTINE_EXERCISES to "SELECT p.userId FROM routine_exercises t LEFT JOIN routines p ON p.id = t.routineId WHERE t.id = ?",
        Tables.ROUTINE_SETS to "SELECT p.userId FROM routine_sets t LEFT JOIN routine_exercises e ON e.id = t.routineExerciseId LEFT JOIN routines p ON p.id = e.routineId WHERE t.id = ?",
        Tables.WORKOUT_EXERCISES to "SELECT p.userId FROM workout_exercises t LEFT JOIN workouts p ON p.id = t.workoutId WHERE t.id = ?",
        Tables.WORKOUT_SETS to "SELECT p.userId FROM workout_sets t LEFT JOIN workout_exercises e ON e.id = t.workoutExerciseId LEFT JOIN workouts p ON p.id = e.workoutId WHERE t.id = ?",
    )

    private val lookup = OwnerLookup { table, id ->
        val sql = ownerSql[table] ?: error("Tabla no sincronizable: $table")
        sdb.query(sql, arrayOf<Any>(id)).use { c ->
            if (!c.moveToFirst()) Owner.Absent
            else if (c.isNull(0)) (if (table == Tables.EXERCISES) Owner.Global else Owner.Orphan)
            else Owner.User(c.getString(0))
        }
    }

    private fun toRec(table: String, c: Cursor, revs: Map<Key, Long>): Rec {
        val data = HashMap<String, kotlinx.serialization.json.JsonElement>()
        var id = ""; var dirty = false; var deletedAt: Long? = null
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            val raw = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> SqlValue.Null
                Cursor.FIELD_TYPE_INTEGER -> SqlValue.Int(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(c.getDouble(i))
                else -> SqlValue.Text(c.getString(i))
            }
            if (name == "dirty") { dirty = c.getLong(i) != 0L; continue }          // estado local, no viaja
            val value = SqlValues.fromSql(table, name, raw)                          // booleanos solo en columnas booleanas; texto es texto
            when (name) {
                "id" -> { id = c.getString(i); data[name] = value }
                "deletedAt" -> { deletedAt = if (c.isNull(i)) null else c.getLong(i); data[name] = value }
                else -> data[name] = value
            }
        }
        return Rec(table, id, deletedAt, JsonObject(data), revs[Key(table, id)] ?: 0L, dirty)
    }

    /**
     * Escribe una fila y su revisión. Antes valida estructura y PROPIEDAD con OwnerGuard (SQL real): una fila de otra cuenta, un id mal
     * formado o un padre ajeno abortan la transacción. Si la fila ya es de este usuario se ACTUALIZA; si no existe se INSERTA; nunca REPLACE.
     * Los valores se convierten con SqlValues (tipado estricto): «true» sigue siendo texto y un booleano solo entra en columnas booleanas.
     */
    fun put(rec: Rec) {
        OwnerGuard.checkWrite(uid, rec, lookup)
        val cols = columns(rec.table)
        val cv = ContentValues()
        for (col in cols) {
            when (col.name) {
                "dirty" -> { cv.put("dirty", if (rec.dirty) 1 else 0); continue }
                "deletedAt" -> { if (rec.deletedAt == null) cv.putNull("deletedAt") else cv.put("deletedAt", rec.deletedAt); continue }
                "userId" -> if (rec.table in USER_OWNED) { cv.put("userId", uid); continue }
            }
            when (val v = SqlValues.toSql(rec.table, col.name, col.type, rec.data[col.name])) {
                SqlValue.Null -> cv.putNull(col.name)
                is SqlValue.Int -> cv.put(col.name, v.v)
                is SqlValue.Real -> cv.put(col.name, v.v)
                is SqlValue.Text -> cv.put(col.name, v.v)
            }
        }
        val owner = lookup.owner(rec.table, rec.id)
        if (owner == Owner.Absent) {
            check(sdb.insert("`${rec.table}`", SQLiteDatabase.CONFLICT_ABORT, cv) != -1L) { "No se pudo escribir ${rec.table}/${rec.id}" }
        } else {
            cv.remove("id")
            check(sdb.update("`${rec.table}`", SQLiteDatabase.CONFLICT_ABORT, cv, "id = ?", arrayOf(rec.id)) == 1) { "No se pudo actualizar ${rec.table}/${rec.id}" }
        }
        setRev(rec.table, rec.id, rec.rev)
    }

    fun remove(table: String, id: String) {
        OwnerGuard.checkDelete(uid, table, id, lookup)
        sdb.execSQL("DELETE FROM `$table` WHERE id = ?", arrayOf<Any>(id))
        setRev(table, id, 0)
    }

    private fun setRev(table: String, id: String, rev: Long) {
        if (rev > 0) sdb.execSQL("INSERT OR REPLACE INTO sync_revs (userId, serverKey, tableName, id, rev) VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(uid, serverKey, table, id, rev))
        else sdb.execSQL("DELETE FROM sync_revs WHERE userId = ? AND serverKey = ? AND tableName = ? AND id = ?", arrayOf<Any>(uid, serverKey, table, id))
    }

    companion object {
        private val USER_OWNED = setOf(Tables.EXERCISES, Tables.ROUTINES, Tables.WORKOUTS, Tables.BODY_MEASUREMENTS, Tables.SCHEDULES, Tables.ACHIEVEMENTS)
    }
}
