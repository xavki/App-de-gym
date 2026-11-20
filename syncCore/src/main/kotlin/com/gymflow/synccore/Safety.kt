package com.gymflow.synccore

import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════
//  H1 — Identidad de la operación: (uid, servidor) atado de extremo a extremo
// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════

/** La sesión o el servidor cambiaron entre preparar una operación y confirmarla (o durante ella). Siempre fatal, nunca se aplica nada. */
class IdentityChangedException(message: String = "La cuenta o el servidor cambiaron durante la operación. No se aplicó nada: vuelve a empezar.") : Exception(message)

object OperationIdentity {
    /** Identidad de una operación: cuenta autenticada + servidor de destino. */
    fun of(uid: String, serverKey: String) = "$uid|$serverKey"
}

/**
 * Algo que está ligado a una identidad fija ([boundIdentity], decidida al crearlo) y que puede consultar la identidad VIVA del
 * dispositivo ([liveIdentity]: la sesión de Firebase y el servidor configurados ahora mismo; null = sin sesión o sin servidor).
 */
interface Identified {
    val boundIdentity: String
    fun liveIdentity(): String?
}

/** Falla si la identidad atada, la viva y la esperada no son TODAS la misma. Se llama antes y dentro de cada transacción. */
fun Identified.requireSame(expected: String) {
    if (boundIdentity != expected || liveIdentity() != expected) throw IdentityChangedException()
}

// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════
//  H2 — Propiedad de las filas: una escritura de un usuario nunca toca filas de otro
// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════

/** Un registro rechazado por estructura o propiedad. Revierte la transacción entera. */
open class RecordRejectedException(message: String) : Exception(message)

/** El id (o el padre) pertenece a OTRA cuenta de este dispositivo. */
class ForeignRowException(message: String) : RecordRejectedException(message)

sealed interface Owner {
    /** No existe ninguna fila con ese id. */
    data object Absent : Owner
    /** Existe, pero su cadena de padres no llega a ningún propietario (hijo huérfano). */
    data object Orphan : Owner
    /** Catálogo global (sin propietario). */
    data object Global : Owner
    data class User(val uid: String) : Owner
}

fun interface OwnerLookup { fun owner(table: String, id: String): Owner }

object OwnerGuard {
    private fun ok(o: Owner, uid: String) = o == Owner.Absent || o == Owner.Orphan || o == Owner.User(uid)

    /** Valida estructura y propiedad ANTES de escribir [rec] como el usuario [uid]. */
    fun checkWrite(uid: String, rec: Rec, lookup: OwnerLookup) {
        if (rec.table !in Tables.ALL) throw RecordRejectedException("Tabla no sincronizable: ${rec.table}")
        if (!UUID_RE.matches(rec.id)) throw RecordRejectedException("Id no válido en ${rec.table}")
        val inner = rec.str("id")
        if (inner != null && !inner.equals(rec.id, ignoreCase = true)) throw RecordRejectedException("El id del contenido no coincide con el del registro en ${rec.table}")
        val existing = lookup.owner(rec.table, rec.id)
        if (!ok(existing, uid)) throw ForeignRowException("El registro ${rec.table}/${rec.id} pertenece a otra cuenta de este dispositivo")
        Tables.PARENT[rec.table]?.let { (pt, f) ->
            val parentId = rec.str(f) ?: throw RecordRejectedException("Falta $f en ${rec.table}/${rec.id}")
            if (!ok(lookup.owner(pt, parentId), uid)) throw ForeignRowException("El padre de ${rec.table}/${rec.id} pertenece a otra cuenta")
        }
    }

    fun checkDelete(uid: String, table: String, id: String, lookup: OwnerLookup) {
        if (!ok(lookup.owner(table, id), uid)) throw ForeignRowException("El registro $table/$id pertenece a otra cuenta de este dispositivo")
    }
}

// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════
//  H5 — Tipos: booleanos, números y cadenas nunca se confunden al pasar de JSON a SQLite y de vuelta
// ═════════════════════════════════════════════════════════════════════════════════════════════════════════════════

class ValueTypeException(message: String) : RecordRejectedException(message)

sealed interface SqlValue {
    data object Null : SqlValue
    data class Int(val v: Long) : SqlValue
    data class Real(val v: Double) : SqlValue
    data class Text(val v: String) : SqlValue
}

object SqlValues {
    /** Únicas columnas booleanas (INTEGER 0/1 en SQLite, booleano en JSON). */
    val BOOLEAN_COLUMNS = setOf("exercises.isCustom", "workout_sets.completed")

    private fun affinity(declared: String): String {
        val d = declared.uppercase()
        return when {
            d.contains("INT") -> "INT"
            d.contains("REAL") || d.contains("FLOA") || d.contains("DOUB") -> "REAL"
            d.contains("CHAR") || d.contains("CLOB") || d.contains("TEXT") -> "TEXT"
            else -> throw ValueTypeException("Tipo de columna no admitido: $declared")
        }
    }

    /** JSON → SQLite, estricto: el tipo del JSON debe corresponder al de la columna; nada se «adivina». */
    fun toSql(table: String, column: String, declaredType: String, value: JsonElement?): SqlValue {
        if (value == null || value is JsonNull) return SqlValue.Null
        val p = value as? JsonPrimitive ?: throw ValueTypeException("Valor no simple en $table.$column")
        val where = "$table.$column"
        if (where in BOOLEAN_COLUMNS) {
            if (p.isString) throw ValueTypeException("$where es booleana: se recibió texto")
            val b = p.booleanOrNull ?: throw ValueTypeException("$where es booleana: se recibió un número")
            return SqlValue.Int(if (b) 1 else 0)
        }
        if (p.isString) {
            if (affinity(declaredType) != "TEXT") throw ValueTypeException("$where no es de texto: se recibió texto")
            return SqlValue.Text(p.content)                      // «true», «false», «1», «null»… son TEXTO, tal cual
        }
        if (p.booleanOrNull != null) throw ValueTypeException("$where no es booleana: se recibió un booleano")
        val n = try { BigDecimal(p.content) } catch (e: NumberFormatException) { throw ValueTypeException("$where: número no válido") }
        return when (affinity(declaredType)) {
            "INT" -> {
                val exact = try { n.toBigIntegerExact().takeIf { it.bitLength() < 64 } } catch (e: ArithmeticException) { null }
                    ?: throw ValueTypeException("$where: se esperaba un entero de 64 bits")
                SqlValue.Int(exact.toLong())
            }
            "REAL" -> {
                val d = n.toDouble()
                if (d.isNaN() || d.isInfinite()) throw ValueTypeException("$where: número fuera de rango")
                SqlValue.Real(d)
            }
            else -> throw ValueTypeException("$where es de texto: se recibió un número")
        }
    }

    /** SQLite → JSON, con el mismo rigor (una columna booleana solo puede contener 0 o 1). */
    fun fromSql(table: String, column: String, v: SqlValue): JsonElement {
        val where = "$table.$column"
        return when (v) {
            SqlValue.Null -> JsonNull
            is SqlValue.Int -> if (where in BOOLEAN_COLUMNS) {
                if (v.v != 0L && v.v != 1L) throw ValueTypeException("$where contiene $v.v y no es booleano")
                JsonPrimitive(v.v == 1L)
            } else JsonPrimitive(v.v)
            is SqlValue.Real -> JsonPrimitive(v.v)
            is SqlValue.Text -> JsonPrimitive(v.v)
        }
    }
}
