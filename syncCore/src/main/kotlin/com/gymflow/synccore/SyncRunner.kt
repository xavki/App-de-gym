package com.gymflow.synccore

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

enum class ConflictReason { BOTH_CHANGED, DELETED_REMOTELY, DELETED_LOCALLY, FIRST_SYNC }

/** Una fila en conflicto: la versión local (si existe) y la del servidor (si existe). Ninguna se descarta hasta que el usuario decide. */
data class ConflictItem(val table: String, val id: String, val local: Rec?, val remote: RemoteRecord?)

/** Conflicto a nivel de AGREGADO (rutina o entreno completo): se resuelve entero para no mezclar versiones. */
data class ConflictGroup(
    val root: Key,
    val label: String,
    val items: List<ConflictItem>,
    val reason: ConflictReason,
    val detectedAt: Long,
    /** true cuando el grupo contiene TODAS las filas del agregado en ambos lados (solo entonces se puede resolver). */
    val complete: Boolean = false,
)

data class SyncMeta(
    val cursor: Long = 0,
    val onboarded: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastErrorKind: String? = null,
)

interface SyncTx {
    suspend fun records(): List<Rec>
    /** Debe rechazar (RecordRejectedException) filas de otra cuenta o mal formadas: nunca sustituye datos ajenos. */
    suspend fun put(rec: Rec)
    /** Borrado físico: solo para filas que nunca llegaron al servidor (rev = 0) o para armonizar duplicados verificados o tras archivarlas. */
    suspend fun remove(table: String, id: String)
    suspend fun conflicts(): List<ConflictGroup>
    suspend fun saveConflict(group: ConflictGroup)
    suspend fun removeConflict(root: Key)
    /** Histórico recuperable de lo que se descartó al resolver un conflicto. */
    suspend fun archive(group: ConflictGroup, resolution: String, at: Long)
    suspend fun meta(): SyncMeta
    suspend fun setMeta(meta: SyncMeta)
}

interface SyncStore : Identified {
    /** Todo o nada. */
    suspend fun <T> transaction(block: suspend SyncTx.() -> T): T
}

sealed class SyncOutcome {
    data object OnboardingRequired : SyncOutcome()
    /** [oversized]: agregados con más filas de las que caben en una petición atómica; se omiten y se avisa (no bloquean al resto). */
    data class Success(val pushed: Int, val pulled: Int, val openConflicts: Int, val oversized: Int = 0) : SyncOutcome()
    data class Failed(val kind: RemoteException.Kind, val retryable: Boolean) : SyncOutcome()
}

/**
 * Motor de sincronización incremental e idempotente.
 *  1. No hace NADA en red hasta que la incorporación inicial (Onboarding) se haya confirmado.
 *  2. SUBE AGREGADOS COMPLETOS: si cambia cualquier fila de una rutina o un entreno se envían todas sus filas con su revisión base,
 *     como un único grupo atómico. Así un cambio hermano concurrente en el servidor se detecta como conflicto de agregado.
 *  3. BAJA TODO lo pendiente antes de aplicar nada (sin límites de página) y aplica en UNA transacción: un agregado nunca se mezcla
 *     entre páginas. Si una fila local sucia de un agregado difiere de lo remoto, el agregado entero se congela como conflicto.
 *  4. Cada paso comprueba que la cuenta y el servidor siguen siendo los mismos (H1); si cambian, se aborta sin escribir nada.
 */
class SyncRunner(
    private val store: SyncStore,
    private val remote: RemoteApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200,
    private val maxAggregateRows: Int = MAX_AGGREGATE_ROWS,
    private val maxPullChanges: Int = MAX_PULL_CHANGES,
) {
    suspend fun run(): SyncOutcome = SyncLock.mutex.withLock {
        val id = store.boundIdentity
        try {
            store.requireSame(id)
            if (!store.transaction { store.requireSame(id); meta() }.onboarded) return@withLock SyncOutcome.OnboardingRequired
            val first = pushAll(id)
            val pulled = pullAll(id)
            val second = pushAll(id)                  // por si la descarga dejó filas pendientes (servidor restaurado…): sin red si no hay nada sucio
            val pushed = first.first + second.first
            val oversized = maxOf(first.second, second.second)
            val open = tx(id) {
                setMeta(meta().copy(lastSuccessAt = clock(), lastErrorKind = null))
                conflicts().size
            }
            SyncOutcome.Success(pushed, pulled, open, oversized)
        } catch (e: IdentityChangedException) {
            SyncOutcome.Failed(RemoteException.Kind.IDENTITY, false)              // sin escribir nada: ni siquiera el último error
        } catch (e: RecordRejectedException) {
            runCatching { tx(id) { setMeta(meta().copy(lastErrorKind = RemoteException.Kind.REJECTED.name)) } }
            SyncOutcome.Failed(RemoteException.Kind.REJECTED, false)
        } catch (e: RemoteException) {
            runCatching { tx(id) { setMeta(meta().copy(lastErrorKind = e.kind.name)) } }
            SyncOutcome.Failed(e.kind, e.retryable)
        }
    }

    /** Transacción que comprueba la identidad al empezar Y justo antes de confirmar. */
    private suspend fun <T> tx(id: String, block: suspend SyncTx.() -> T): T = store.transaction {
        store.requireSame(id)
        val r = block()
        store.requireSame(id)
        r
    }

    /** Llamada de red entre dos comprobaciones de identidad (la respuesta de una cuenta nunca se aplica bajo otra). */
    private suspend fun <T> guarded(id: String, call: suspend () -> T): T {
        store.requireSame(id)
        val r = call()
        store.requireSame(id)
        return r
    }

    // ───────────── subida ─────────────

    private var rootCache: Map<Key, Key> = emptyMap()
    private var lastOversized = 0

    private suspend fun pushAll(id: String): Pair<Int, Int> {
        var total = 0
        var rounds = 0
        lastOversized = 0
        while (rounds++ < 10_000) {
            val batch = tx(id) { nextBatch() }
            if (batch.isEmpty()) break
            val resp = guarded(id) { remote.push(batch.map { it.toPushChange(group = groupOf(it, batch)) }) }
            val byKey = resp.results.associateBy { Key(it.table, it.id) }
            if (batch.any { it.key !in byKey }) throw RemoteException(RemoteException.Kind.SERVER, "Respuesta incompleta del servidor")
            total += tx(id) { applyPushResults(batch, byKey) }
        }
        return total to lastOversized
    }

    /**
     * Agregados completos con alguna fila sucia, no congelados, padres primero. Un agregado nunca se reparte entre lotes (el primero se
     * admite aunque supere [pushBatch]); los que no caben en UNA petición atómica se omiten y se cuentan en [lastOversized].
     */
    private suspend fun SyncTx.nextBatch(): List<Rec> {
        val all = records()
        val idx = RecIndex(all)
        val frozen = conflicts().map { it.root }.toSet()
        rootCache = all.associate { it.key to (idx.rootOf(it) ?: it.key) }
        val roots = all.filter { it.dirty }.map { rootCache.getValue(it.key) }.distinct().filter { it !in frozen }
            .sortedBy { Tables.ALL.indexOf(it.table) }
        val out = mutableListOf<Rec>()
        var skipped = 0
        for (root in roots) {
            val rows = (idx.aggregate(root).ifEmpty { all.filter { it.dirty && rootCache[it.key] == root } }).sortedBy { Tables.ALL.indexOf(it.table) }
            if (rows.size > maxAggregateRows) { skipped++; continue }
            if (out.isNotEmpty() && out.size + rows.size > pushBatch) break
            out += rows
        }
        lastOversized = skipped
        return out
    }

    private fun groupOf(r: Rec, batch: List<Rec>): String? {
        val root = rootCache[r.key] ?: return null
        return if (batch.count { rootCache[it.key] == root } > 1) "${root.table}:${root.id}" else null
    }

    private suspend fun SyncTx.applyPushResults(batch: List<Rec>, results: Map<Key, PushResult>): Int {
        var done = 0
        val current = RecIndex(records())
        val newConflicts = mutableMapOf<Key, MutableList<ConflictItem>>()
        for (pushed in batch) {
            val r = results.getValue(pushed.key)
            val now = current.byKey[pushed.key] ?: continue
            when (r.status) {
                PushStatus.ACCEPTED, PushStatus.UNCHANGED -> {
                    // Una fila que estaba sucia y no cambió mientras subía queda limpia; si cambió, sigue sucia pero con la nueva base.
                    // Una fila que iba limpia (solo acompañaba al agregado) conserva su estado: solo se actualiza la revisión.
                    val unchanged = Content.same(now, pushed) && now.deleted == pushed.deleted
                    put(now.copy(rev = r.rev, dirty = if (pushed.dirty) !unchanged else now.dirty)); if (pushed.dirty) done++
                }
                PushStatus.CONFLICT -> {
                    val server = r.server ?: throw RemoteException(RemoteException.Kind.SERVER, "Conflicto sin versión del servidor")
                    val root = current.rootOf(now) ?: now.key
                    newConflicts.getOrPut(root) { mutableListOf() } += ConflictItem(now.table, now.id, now, server)
                }
                PushStatus.BLOCKED -> {}     // su agregado entró en conflicto en otra fila; sigue como estaba y congelada
                else -> throw RemoteException(RemoteException.Kind.SERVER, "Estado de subida desconocido: ${r.status}")
            }
        }
        for ((root, items) in newConflicts) saveConflictMerged(root, items, labelOf(current, root), reasonOf(items), complete = false)
        if (newConflicts.isNotEmpty()) setMeta(meta().copy(cursor = 0))     // nueva descarga completa: el grupo recogerá TODAS las versiones del servidor
        return done
    }

    // ───────────── descarga ─────────────

    /**
     * Descarga todo lo pendiente (todas las páginas) SIN aplicar nada y lo aplica de una vez en una sola transacción, junto con el
     * cursor. Un corte a mitad de la descarga no deja nada a medias. Si aparecen conflictos nuevos, una segunda pasada completa
     * (cursor 0) recoge todas las versiones del servidor para que puedan resolverse.
     */
    private suspend fun pullAll(id: String): Int {
        var applied = 0
        var passes = 0
        while (passes++ < 2) {
            val start = tx(id) { meta().cursor }
            val changes = downloadFrom(id, start)
            val (n, newOpen) = tx(id) {
                val res = applyAll(changes.records)
                setMeta(meta().copy(cursor = changes.cursor))
                if (start == 0L) conflicts().filter { !it.complete }.forEach { saveConflict(it.copy(complete = true)) }   // descarga completa terminada
                res
            }
            applied += n
            if (newOpen && start != 0L) {
                tx(id) { setMeta(meta().copy(cursor = 0)) }                 // pasada completa para completar los conflictos nuevos
                continue
            }
            break
        }
        return applied
    }

    private class Downloaded(val records: List<RemoteRecord>, val cursor: Long)

    private suspend fun downloadFrom(id: String, start: Long): Downloaded {
        var cursor = start
        val all = LinkedHashMap<Key, RemoteRecord>()
        var guard = 0
        do {
            val page = guarded(id) { remote.pull(cursor, pageSize) }
            for (c in page.changes) {
                val k = Key(c.table, c.id)
                if (all[k]?.let { it.version >= c.version } != true) all[k] = c
            }
            if (all.size > maxPullChanges) throw RemoteException(RemoteException.Kind.SERVER, "Demasiados cambios para aplicar de una vez")
            if (page.hasMore && page.cursor <= cursor && !page.reset) throw RemoteException(RemoteException.Kind.SERVER, "Cursor sin avance")
            cursor = page.cursor
            if (page.reset) { /* se marca abajo: el servidor tiene menos historial que mi cursor */ resetSeen = true }
        } while (page.hasMore && guard++ < 100_000)
        return Downloaded(all.values.toList(), cursor)
    }

    private var resetSeen = false

    /** Devuelve (filas aplicadas, ¿se crearon conflictos nuevos?). Todo el conjunto descargado, a la vez, agregado a agregado. */
    private suspend fun SyncTx.applyAll(changes: List<RemoteRecord>): Pair<Int, Boolean> {
        if (resetSeen) {
            // El servidor tiene MENOS historial que mi cursor (p. ej. se restauró una copia): todo lo local debe poder volver a subirse.
            // La base pasa a 0, así que nada se pisa: lo idéntico se reconoce y lo distinto sale como conflicto.
            records().filter { !it.dirty || it.rev != 0L }.forEach { put(it.copy(rev = 0, dirty = true)) }
            resetSeen = false
        }
        val local = records().associateBy { it.key }.toMutableMap()
        val remoteByKey = changes.associateBy { Key(it.table, it.id) }
        val existingGroups = conflicts().associateBy { it.root }
        fun rootOfRemote(c: RemoteRecord): Key {
            var key = Key(c.table, c.id); var data = c.data; var guard = 0
            while (key.table in Tables.PARENT && guard++ < 4) {
                val (pt, f) = Tables.PARENT.getValue(key.table)
                val pid = (data[f] as? JsonPrimitive)?.content ?: return key
                key = Key(pt, pid)
                data = remoteByKey[key]?.data ?: local[key]?.data ?: return key
            }
            return key
        }
        val byRoot = changes.groupBy { rootOfRemote(it) }
        val idxBefore = RecIndex(local.values)
        var n = 0
        var created = false
        for ((root, list) in byRoot) {
            val localAgg = idxBefore.aggregate(root).ifEmpty { list.mapNotNull { local[Key(it.table, it.id)] } }
            val diffs = list.filter { c -> local[Key(c.table, c.id)]?.let { !Content.same(it, c.toRec()) } != false }
            val dirtyLocal = localAgg.any { it.dirty }
            val frozen = root in existingGroups
            if (frozen || (dirtyLocal && diffs.isNotEmpty())) {
                // El agregado se congela ENTERO: nada de lo remoto se aplica; todas las versiones del servidor se guardan en el conflicto
                val items = list.map { c -> ConflictItem(c.table, c.id, local[Key(c.table, c.id)], c) } +
                    localAgg.filter { it.dirty && list.none { c -> c.table == it.table && c.id == it.id } }.map { ConflictItem(it.table, it.id, it, null) }
                if (!frozen) created = true
                saveConflictMerged(root, items, labelOf(idxBefore, root), reasonOf(items), complete = false, existing = existingGroups[root])
                continue
            }
            for (c in list) {
                val k = Key(c.table, c.id)
                val mine = local[k]
                val incoming = c.toRec(dirty = false)
                when {
                    mine == null -> { put(incoming); local[k] = incoming; n++ }
                    !mine.dirty -> if (c.rev >= mine.rev) { put(incoming); local[k] = incoming; n++ }
                    else -> { val adopted = mine.copy(rev = c.rev, dirty = false); put(adopted); local[k] = adopted; n++ }    // idéntica: adopta la revisión
                }
            }
        }
        return n to created
    }

    // ───────────── conflictos ─────────────

    private fun labelOf(idx: RecIndex, root: Key): String = idx.byKey[root]?.str("name") ?: root.id
    private fun reasonOf(items: List<ConflictItem>): ConflictReason = when {
        items.any { it.remote?.deletedAt != null && it.local?.deleted == false } -> ConflictReason.DELETED_REMOTELY
        items.any { it.local?.deleted == true && it.remote?.deletedAt == null } -> ConflictReason.DELETED_LOCALLY
        else -> ConflictReason.BOTH_CHANGED
    }

    private suspend fun SyncTx.saveConflictMerged(
        root: Key, items: List<ConflictItem>, label: String, reason: ConflictReason, complete: Boolean, existing: ConflictGroup? = null,
    ) {
        val prior = existing ?: conflicts().firstOrNull { it.root == root }
        // si ya había una versión de la fila, prevalece la que llega ahora (la más reciente del servidor)
        val merged = (prior?.items.orEmpty() + items).associateBy { Key(it.table, it.id) }.values.toList()
        saveConflict(ConflictGroup(root, label, merged, prior?.reason ?: reason, prior?.detectedAt ?: clock(), complete = complete && (prior?.complete ?: true)))
    }

    companion object {
        /** Debe caber en UNA petición atómica del servidor (máx. 2000 cambios). */
        const val MAX_AGGREGATE_ROWS = 1900
        const val MAX_PULL_CHANGES = 500_000
    }
}
