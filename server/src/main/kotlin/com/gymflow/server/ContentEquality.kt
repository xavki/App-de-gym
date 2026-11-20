package com.gymflow.server

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Igualdad de CONTENIDO de negocio de dos versiones de una fila (misma semántica que Content en syncCore, comprobada con
 * contract/content-equality-cases.json): ignora createdAt/updatedAt/userId/deletedAt dentro de `data`, trata null = ausente
 * y normaliza los números (62.50 = 62.5). El estado borrado/vivo se compara aparte.
 */
object ContentEquality {
    private val META = setOf("createdAt", "updatedAt", "userId", "deletedAt")

    fun same(aData: JsonObject, aDeleted: Boolean, bData: JsonObject, bDeleted: Boolean): Boolean =
        aDeleted == bDeleted && canonical(business(aData)) == canonical(business(bData))

    private fun business(o: JsonObject) = JsonObject(o.filterKeys { it !in META })

    fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e.entries.filter { it.value !is JsonNull }.sortedBy { it.key }) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(JsonPrimitive(k).toString()).append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }
                sb.append(']')
            }
            is JsonPrimitive -> {
                val num = if (!e.isString) runCatching { BigDecimal(e.content) }.getOrNull() else null
                if (num != null) sb.append(if (num.signum() == 0) "0" else num.stripTrailingZeros().toPlainString()) else sb.append(e.toString())
            }
        }
    }
}
