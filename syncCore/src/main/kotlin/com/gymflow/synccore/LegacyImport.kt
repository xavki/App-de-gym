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

suspend fun runLegacyImport(
    uid: String,
    items: List<LegacyItem>,
    ownerOf: suspend (LegacyItem) -> Owner,
    write: suspend (LegacyItem) -> Unit,
    stillActive: () -> Boolean,
): LegacyReport {
    var written = 0; var already = 0
    val collisions = mutableListOf<LegacyItem>()
    for (item in items) {
        if (!stillActive()) return LegacyReport(written, already, collisions, item, IdentityChangedException())
        try {
            when (LegacyImportPolicy.decide(uid, ownerOf(item))) {
                LegacyDecision.ALREADY_IMPORTED -> already++
                LegacyDecision.FOREIGN_COLLISION -> collisions += item
                LegacyDecision.WRITE -> { write(item); written++ }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return LegacyReport(written, already, collisions, item, e)
        }
    }
    return LegacyReport(written, already, collisions, null, null)
}
