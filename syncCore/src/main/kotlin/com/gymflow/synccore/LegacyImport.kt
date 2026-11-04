package com.gymflow.synccore

/** Una unidad lógica (agregado) de la importación heredada de Firestore. */
data class LegacyItem(val table: String, val id: String)

enum class LegacyDecision { WRITE, ALREADY_IMPORTED, FOREIGN_COLLISION }

/**
 * Importación heredada INCREMENTAL (no globalmente atómica): cada agregado se escribe en su propia transacción y, si uno falla, los ya
 * escritos se CONSERVAN y el reintento los reconoce como importados (ids estables) y continúa. Una fila con el mismo id perteneciente a
 * OTRA cuenta (o al catálogo global) nunca se sobrescribe ni se confunde con «ya importado»: se omite y se informa.
 */
object LegacyImportPolicy {
    fun decide(uid: String, owner: Owner): LegacyDecision = when (owner) {
        Owner.Absent, Owner.Orphan -> LegacyDecision.WRITE
        is Owner.User -> if (owner.uid == uid) LegacyDecision.ALREADY_IMPORTED else LegacyDecision.FOREIGN_COLLISION
        Owner.Global -> LegacyDecision.FOREIGN_COLLISION
    }
}

data class LegacyReport(
    val written: Int,
    val alreadyImported: Int,
    val collisions: List<LegacyItem>,
    /** Primer agregado que falló (null = terminó); los posteriores NO se intentaron. */
    val failedAt: LegacyItem?,
    val failure: Throwable?,
) {
    /** Terminó sin fallos (puede haber colisiones omitidas, que se informan aparte). */
    val complete: Boolean get() = failedAt == null
}

/** Entre decidir «escribir» y escribir, otra cuenta/proceso reclamó el id: se aborta ese agregado (el reintento lo reevaluará). */
class LegacyOwnershipChangedException(val item: LegacyItem) : Exception("El propietario de ${item.table}/${item.id} cambió durante la importación")

/**
 * @param ownerOf   propietario REAL de una fila (consulta suspendible).
 * @param write     escribe UN agregado. Se invoca SIEMPRE dentro de [atomically] y tras revalidar cuenta y propietario en esa misma unidad.
 * @param atomically ejecuta su bloque en una unidad transaccional (en Android: `db.withTransaction`, que se une a las transacciones
 *                  internas de cada `save*`). Si el bloque lanza, se revierte. Por defecto ejecuta el bloque tal cual (pruebas).
 * @param onComplete se ejecuta una sola vez, solo si TODO terminó y la cuenta sigue activa (logros finales, marca de «hecho»…).
 *
 * Contrato de identidad (R4-02): se comprueba [stillActive] (1) antes de consultar el propietario, (2) después de consultarlo,
 * (3) otra vez DENTRO de la transacción justo antes de persistir —junto con una relectura del propietario— y (4) antes de [onComplete].
 * [CancellationException] siempre se propaga.
 */
suspend fun runLegacyImport(
    uid: String,
    items: List<LegacyItem>,
    ownerOf: suspend (LegacyItem) -> Owner,
    write: suspend (LegacyItem) -> Unit,
    stillActive: () -> Boolean,
    atomically: suspend (suspend () -> Unit) -> Unit = { it() },
    onComplete: suspend () -> Unit = {},
): LegacyReport {
    var written = 0; var already = 0
    val collisions = mutableListOf<LegacyItem>()
    fun fail(item: LegacyItem, e: Throwable) = LegacyReport(written, already, collisions, item, e)
    for (item in items) {
        if (!stillActive()) return fail(item, IdentityChangedException())
        try {
            val owner = ownerOf(item)
            if (!stillActive()) return fail(item, IdentityChangedException())          // la cuenta cambió DURANTE la consulta
            when (LegacyImportPolicy.decide(uid, owner)) {
                LegacyDecision.ALREADY_IMPORTED -> already++
                LegacyDecision.FOREIGN_COLLISION -> collisions += item
                LegacyDecision.WRITE -> {
                    atomically {
                        if (!stillActive()) throw IdentityChangedException()                                 // justo antes de persistir
                        if (LegacyImportPolicy.decide(uid, ownerOf(item)) != LegacyDecision.WRITE) throw LegacyOwnershipChangedException(item)
                        if (!stillActive()) throw IdentityChangedException()
                        write(item)
                    }
                    written++
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(item, e)
        }
    }
    val last = LegacyItem("onComplete", "")
    if (!stillActive()) return fail(last, IdentityChangedException())
    try { onComplete() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return fail(last, e) }
    return LegacyReport(written, already, collisions, null, null)
}
