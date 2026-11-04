package com.gymflow.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gymflow.synccore.Key
import com.gymflow.synccore.Rec
import com.gymflow.synccore.Tables
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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

    private fun revs(): Map<Key, Long> = sdb.query("SELECT tableName, id, rev FROM sync_revs WHERE serverKey = ?", arrayOf<Any>(serverKey)).use { c ->
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

    fun exists(table: String, id: String): Boolean =
        sdb.query("SELECT 1 FROM `$table` WHERE id = ?", arrayOf<Any>(id)).use { it.moveToFirst() }

    private fun toRec(table: String, c: Cursor, revs: Map<Key, Long>): Rec {
        val data = HashMap<String, kotlinx.serialization.json.JsonElement>()
        var id = ""; var dirty = false; var deletedAt: Long? = null
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            val isBool = "$table.$name" in BOOLEAN_COLUMNS
            val value = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> if (isBool) JsonPrimitive(c.getLong(i) != 0L) else JsonPrimitive(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                else -> JsonPrimitive(c.getString(i))
            }
            when (name) {
                "dirty" -> dirty = c.getLong(i) != 0L           // estado local, no viaja
                "id" -> { id = c.getString(i); data[name] = value }
                "deletedAt" -> { deletedAt = if (c.isNull(i)) null else c.getLong(i); data[name] = value }
                else -> data[name] = value
            }
        }
        return Rec(table, id, deletedAt, JsonObject(data), revs[Key(table, id)] ?: 0L, dirty)
    }

    /** Escribe (INSERT OR REPLACE) una fila y su estado de sincronización. Solo columnas que existen en esta versión de la app. */
    fun put(rec: Rec) {
        val cols = columns(rec.table)
        val cv = ContentValues()
        for (col in cols) {
            when (col.name) {
                "dirty" -> { cv.put("dirty", if (rec.dirty) 1 else 0); continue }
                "deletedAt" -> { if (rec.deletedAt == null) cv.putNull("deletedAt") else cv.put("deletedAt", rec.deletedAt); continue }
                "userId" -> if (rec.table in USER_OWNED) { cv.put("userId", uid); continue }
            }
            val v = rec.data[col.name]
            if (v == null || v is JsonNull) { cv.putNull(col.name); continue }
            val p = v as? JsonPrimitive ?: error("Valor no simple en ${rec.table}.${col.name}")
            val bool = p.booleanOrNull
            when {
                bool != null -> cv.put(col.name, if (bool) 1 else 0)
                p.isString -> cv.put(col.name, p.content)
                col.type.contains("INT") -> cv.put(col.name, p.content.toDouble().toLong())
                col.type.contains("REAL") || col.type.contains("FLOA") || col.type.contains("DOUB") -> cv.put(col.name, p.content.toDouble())
                else -> cv.put(col.name, p.content)
            }
        }
        check(sdb.insert("`${rec.table}`", SQLiteDatabase.CONFLICT_REPLACE, cv) != -1L) { "No se pudo escribir ${rec.table}/${rec.id}" }
        setRev(rec.table, rec.id, rec.rev)
    }

    fun remove(table: String, id: String) {
        sdb.execSQL("DELETE FROM `$table` WHERE id = ?", arrayOf<Any>(id))
        setRev(table, id, 0)
    }

    private fun setRev(table: String, id: String, rev: Long) {
        if (rev > 0) sdb.execSQL("INSERT OR REPLACE INTO sync_revs (serverKey, tableName, id, rev) VALUES (?, ?, ?, ?)", arrayOf<Any>(serverKey, table, id, rev))
        else sdb.execSQL("DELETE FROM sync_revs WHERE serverKey = ? AND tableName = ? AND id = ?", arrayOf<Any>(serverKey, table, id))
    }

    companion object {
        private val BOOLEAN_COLUMNS = setOf("exercises.isCustom", "workout_sets.completed")
        private val USER_OWNED = setOf(Tables.EXERCISES, Tables.ROUTINES, Tables.WORKOUTS, Tables.BODY_MEASUREMENTS, Tables.SCHEDULES, Tables.ACHIEVEMENTS)
    }
}
