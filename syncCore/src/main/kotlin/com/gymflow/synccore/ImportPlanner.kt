package com.gymflow.synccore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Un solo proceso a la vez puede modificar datos de usuario de forma "masiva": importación, incorporación inicial y sincronización. */
object SyncLock { val mutex = Mutex() }

enum class Identity { MATCH, MISMATCH, UNKNOWN }
enum class ItemStatus { NEW, IDENTICAL, CONFLICT, DELETED_LOCALLY }

data class PlanItem(val kind: Kind, val rootKey: Key, val label: String, val status: ItemStatus, val diffs: List<String>) {
    val rootId get() = rootKey.id
}

/** Elecciones explícitas del usuario sobre una vista previa. Por defecto NO se importa nada que difiera de lo local. */
data class ImportChoices(
    val allowForeignAccount: Boolean = false,
    /** Conflictos o borrados locales que el usuario quiere conservar COMO COPIA (nuevos ids; nunca sobrescribe). */
    val importAsCopy: Set<Key> = emptySet(),
)

class ImportPlan internal constructor(
    /** Cuenta+servidor para los que se preparó. Confirmar bajo otra identidad se rechaza. */
    val ownerIdentity: String,
    val identity: Identity,
    val items: List<PlanItem>,
    val invalid: List<ItemIssue>,
    val warnings: List<String>,
    val fingerprint: String,
    val sourceHash: String,
    internal val aggregates: Map<Key, ImportAggregate>,
) {
    fun count(s: ItemStatus) = items.count { it.status == s }
    val newCount get() = count(ItemStatus.NEW)
    val identicalCount get() = count(ItemStatus.IDENTICAL)
    val conflictCount get() = items.count { it.status == ItemStatus.CONFLICT || it.status == ItemStatus.DELETED_LOCALLY }
    val invalidCount get() = invalid.size
    fun canApply(choices: ImportChoices = ImportChoices()): Boolean =
        (identity != Identity.MISMATCH || choices.allowForeignAccount) &&
            (newCount > 0 || items.any { (it.status == ItemStatus.CONFLICT || it.status == ItemStatus.DELETED_LOCALLY) && it.rootKey in choices.importAsCopy })
}

/** La base local cambió entre la vista previa y la confirmación: hay que volver a analizar. */
class StaleImportPlanException : Exception("La base de datos cambió desde la vista previa. Vuelve a analizar el archivo.")

/** La verificación posterior a la escritura falló: la transacción se revierte. */
class ImportIntegrityException(message: String) : Exception(message)

object ImportPlanner {
    /** [local] = TODAS las filas locales del usuario (incluidos borrados lógicos), leídas en una transacción coherente. */
    fun plan(parsed: ParsedExport, local: Collection<Rec>, activeUid: String, ownerIdentity: String = activeUid): ImportPlan {
        // N-03: la cuenta con la que se interpreta el archivo y la de la operación deben ser la misma (misma captura, antes de suspender)
        if (ownerIdentity != activeUid && ownerIdentity.substringBefore('|') != activeUid) throw IdentityChangedException()
        val idx = RecIndex(local)
        val identity = when {
            parsed.fileUserId == null -> Identity.UNKNOWN
            parsed.fileUserId == activeUid -> Identity.MATCH
            else -> Identity.MISMATCH
        }
        val items = mutableListOf<PlanItem>()
        val aggs = mutableMapOf<Key, ImportAggregate>()
        val warnings = parsed.warnings.toMutableList()
        for (a in parsed.aggregates) {
            val rootKey = a.root.key
            aggs[rootKey] = a
            val existing = idx.byKey[rootKey]
            val label = a.label
            var status: ItemStatus
            var diffs = emptyList<String>()
            if (existing == null) {
                // Raíz nueva; pero si algún id hijo ya existe en otro sitio, es una colisión: no se mezcla en silencio
                val clash = a.records.drop(1).filter { idx.byKey.containsKey(it.key) }
                if (clash.isEmpty()) status = ItemStatus.NEW
                else { status = ItemStatus.CONFLICT; diffs = clash.take(5).map { "id de ${it.table} ya usado por otro registro" } }
            } else if (existing.deleted) {
                status = ItemStatus.DELETED_LOCALLY      // borrado a propósito en este dispositivo: no se resucita sin decisión del usuario
            } else {
                val mine = AggregateView.of(a.kind, rootKey, idx)
                val theirs = AggregateView.of(a.kind, rootKey, RecIndex(a.records))
                if (mine != null && theirs != null && AggregateView.equal(mine, theirs)) status = ItemStatus.IDENTICAL
                else { status = ItemStatus.CONFLICT; diffs = AggregateView.diff(mine, theirs) }
            }
            if (status == ItemStatus.CONFLICT || status == ItemStatus.DELETED_LOCALLY) {
                // ¿Ya se importó este mismo archivo como copia? Entonces repetir la importación no hace nada (idempotente)
                val copy = asCopy(a, parsed.sourceHash)
                val copyRoot = copy.firstOrNull()
                if (copyRoot != null && idx.byKey.containsKey(copyRoot.key)) {
                    val mine = AggregateView.of(a.kind, copyRoot.key, idx)
                    val theirs = AggregateView.of(a.kind, copyRoot.key, RecIndex(copy))
                    if (mine != null && theirs != null && AggregateView.equal(mine, theirs)) { status = ItemStatus.IDENTICAL; diffs = listOf("ya importado como copia") }
                }
            }
            items += PlanItem(a.kind, rootKey, label, status, diffs)
        }
        // Mismo nombre y distinto id: NO se deduplica; solo se avisa
        val localNames = local.filter { it.table == Tables.ROUTINES && !it.deleted }.associate { it.id to it.str("name")?.trim()?.lowercase() }
        val sameName = parsed.aggregates.filter { it.kind == Kind.ROUTINE && it.rootId !in localNames }
            .count { a -> a.label.trim().lowercase() in localNames.values }
        if (sameName > 0) warnings += "$sameName rutina(s) del archivo tienen el mismo nombre que otras locales pero distinto id: se tratan como distintas (no se fusionan)."
        return ImportPlan(ownerIdentity, identity, items, parsed.invalid, warnings, Content.fingerprint(local), parsed.sourceHash, aggs)
    }

    /** Filas que se escribirían con [choices] (sin aplicar nada). */
    internal fun rowsToInsert(plan: ImportPlan, choices: ImportChoices): List<Rec> {
        val out = mutableListOf<Rec>()
        for (item in plan.items) {
            val key = item.rootKey
            val agg = plan.aggregates.getValue(key)
            when (item.status) {
                ItemStatus.NEW -> out += agg.records
                ItemStatus.IDENTICAL -> {}
                ItemStatus.CONFLICT, ItemStatus.DELETED_LOCALLY -> if (key in choices.importAsCopy) out += asCopy(agg, plan.sourceHash)
            }
        }
        return out
    }

    /** Copia determinista (mismo archivo ⇒ mismos ids ⇒ idempotente) con ids nuevos y nombre marcado. Logros: no se copian. */
    internal fun asCopy(a: ImportAggregate, sourceHash: String): List<Rec> {
        if (a.kind == Kind.ACHIEVEMENT) return emptyList()
        val map = a.records.associate { it.id to stableUuid("copy:${sourceHash.take(16)}:${it.id}") }
        fun JsonObject.with(f: String, v: String) = JsonObject(this + (f to JsonPrimitive(v)))
        return a.records.map { r ->
            var d = r.data.with("id", map.getValue(r.id))
            Tables.PARENT[r.table]?.second?.let { f -> r.str(f)?.let { old -> d = d.with(f, map.getValue(old)) } }
            if (r.key == a.root.key && r.table != Tables.BODY_MEASUREMENTS) r.str("name")?.let { d = d.with("name", "$it (importado)") }
            r.copy(id = map.getValue(r.id), data = d)
        }
    }
}

/** Abstracción mínima de la base local para importar (Room en la app; memoria en las pruebas). */
interface RecTx {
    suspend fun all(): List<Rec>
    /** Debe fallar si la fila ya existe (la importación nunca sobrescribe). */
    suspend fun insert(rec: Rec)
}

interface RecStore : Identified {
    /** Todo o nada: si [block] lanza, NO queda ningún cambio. */
    suspend fun <T> transaction(block: suspend RecTx.() -> T): T
}

data class ImportResult(val insertedRows: Int, val insertedAggregates: Int, val skippedIdentical: Int, val skippedConflicts: Int, val copies: Int)

object ImportExecutor {
    suspend fun apply(store: RecStore, plan: ImportPlan, choices: ImportChoices = ImportChoices()): ImportResult {
        store.requireSame(plan.ownerIdentity)                        // H1: la cuenta/servidor de la confirmación deben ser los de la vista previa
        require(plan.canApply(choices)) { "El plan no se puede aplicar con estas opciones" }
        return SyncLock.mutex.withLock {
            store.transaction {
                store.requireSame(plan.ownerIdentity)
                val before = all()
                if (Content.fingerprint(before) != plan.fingerprint) throw StaleImportPlanException()
                val rows = ImportPlanner.rowsToInsert(plan, choices).map { it.copy(rev = 0, dirty = true) }
                rows.forEach { insert(it) }
                // Verificación dentro de la misma transacción: lo escrito existe, es idéntico y tiene todos sus padres
                val after = RecIndex(all())
                for (r in rows) {
                    val back = after.byKey[r.key] ?: throw ImportIntegrityException("No se encontró ${r.table}/${r.id} tras escribirlo")
                    if (!Content.same(back, r)) throw ImportIntegrityException("Contenido distinto tras escribir ${r.table}/${r.id}")
                    Tables.PARENT[r.table]?.let { (pt, f) -> if (after[pt, r.str(f) ?: ""] == null) throw ImportIntegrityException("Falta el padre de ${r.table}/${r.id}") }
                }
                val untouched = before.associateBy { it.key }
                for ((k, old) in untouched) if (!Content.same(after.byKey[k] ?: throw ImportIntegrityException("Se perdió ${k.table}/${k.id}"), old)) throw ImportIntegrityException("Cambió ${k.table}/${k.id}")
                store.requireSame(plan.ownerIdentity)                        // H1: también justo antes de confirmar la transacción
                val copies = plan.items.count { (it.status == ItemStatus.CONFLICT || it.status == ItemStatus.DELETED_LOCALLY) && it.rootKey in choices.importAsCopy }
                ImportResult(
                    insertedRows = rows.size,
                    insertedAggregates = plan.newCount + copies,
                    skippedIdentical = plan.identicalCount,
                    skippedConflicts = plan.conflictCount - copies,
                    copies = copies,
                )
            }
        }
    }
}
