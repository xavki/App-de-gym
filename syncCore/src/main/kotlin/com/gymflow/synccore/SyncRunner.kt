package com.gymflow.synccore

import kotlinx.coroutines.sync.withLock

enum class ConflictReason { BOTH_CHANGED, DELETED_REMOTELY, DELETED_LOCALLY, FIRST_SYNC }

/** Una fila en conflicto: la versión local (si existe) y la del servidor (si existe). Ninguna se descarta hasta que el usuario decide. */
data class ConflictItem(val table: String, val id: String, val local: Rec?, val remote: RemoteRecord?)

/** Conflicto a nivel de AGREGADO (rutina o entreno completos): se resuelve entero para no mezclar versiones. */
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
    suspend fun put(rec: Rec)
    /** Borrado físico: solo para filas que nunca llegaron al servidor (rev = 0) o para armonizar duplicados verificados. */
    suspend fun remove(table: String, id: String)
    suspend fun conflicts(): List<ConflictGroup>
    suspend fun saveConflict(group: ConflictGroup)
    suspend fun removeConflict(root: Key)
    /** Histórico recuperable de lo que se descartó al resolver un conflicto. */
    suspend fun archive(group: ConflictGroup, resolution: String, at: Long)
    suspend fun meta(): SyncMeta
    suspend fun setMeta(meta: SyncMeta)
}

interface SyncStore {
    /** Todo o nada. */
    suspend fun <T> transaction(block: suspend SyncTx.() -> T): T
}

sealed class SyncOutcome {
    data object OnboardingRequired : SyncOutcome()
    data class Success(val pushed: Int, val pulled: Int, val openConflicts: Int) : SyncOutcome()
    data class Failed(val kind: RemoteException.Kind, val retryable: Boolean) : SyncOutcome()
}

/**
 * Motor de sincronización incremental e idempotente. Room (vía [SyncStore]) es la fuente local; el servidor solo intercambia.
 *  1. No hace NADA en red hasta que la incorporación inicial (Onboarding) se haya confirmado.
 *  2. Sube las filas sucias (agregados completos, padres primero) con su revisión base; los conflictos se guardan, no se resuelven por fecha.
 *  3. Baja los cambios desde el cursor; una fila local sucia que difiere de la remota crea un conflicto de agregado.
 */
class SyncRunner(
    private val store: SyncStore,
    private val remote: RemoteApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200,
) {
    suspend fun run(): SyncOutcome = SyncLock.mutex.withLock {
        val meta0 = store.transaction { meta() }
        if (!meta0.onboarded) return@withLock SyncOutcome.OnboardingRequired
        try {
            var pushed = pushAll()
            val pulled = pullAll()
            pushed += pushAll()          // por si la descarga dejó filas pendientes (p. ej. servidor restaurado): sin red si no hay nada sucio
            val open = store.transaction {
                setMeta(meta().copy(lastSuccessAt = clock(), lastErrorKind = null))
                conflicts().size
            }
            SyncOutcome.Success(pushed, pulled, open)
        } catch (e: RemoteException) {
            store.transaction { setMeta(meta().copy(lastErrorKind = e.kind.name)) }
            SyncOutcome.Failed(e.kind, e.retryable)
        }
    }

    // ───────────── subida ─────────────

    private suspend fun pushAll(): Int {
        var total = 0
        var rounds = 0
        while (rounds++ < 10_000) {
            val batch = store.transaction { nextBatch() }
            if (batch.isEmpty()) break
            val resp = remote.push(batch.map { it.toPushChange(group = groupOf(it, batch)) })
            val byKey = resp.results.associateBy { Key(it.table, it.id) }
            if (batch.any { it.key !in byKey }) throw RemoteException(RemoteException.Kind.SERVER, "Respuesta incompleta del servidor")
            total += store.transaction { applyPushResults(batch, byKey) }
        }
        return total
    }

    private var rootCache: Map<Key, Key> = emptyMap()

    /** Filas sucias de agregados no bloqueados; un agregado nunca se reparte entre lotes (salvo que supere el lote). */
    private suspend fun SyncTx.nextBatch(): List<Rec> {
        val all = records()
        val idx = RecIndex(all)
        val frozen = conflicts().map { it.root }.toSet()
        rootCache = all.associate { it.key to (idx.rootOf(it) ?: it.key) }
        val dirty = all.filter { it.dirty && rootCache.getValue(it.key) !in frozen }
        val roots = dirty.groupBy { rootCache.getValue(it.key) }.entries.sortedBy { Tables.ALL.indexOf(it.key.table) }
        val out = mutableListOf<Rec>()
        for ((_, rows) in roots) {
            if (out.isNotEmpty() && out.size + rows.size > pushBatch) break
            out += rows.sortedBy { Tables.ALL.indexOf(it.table) }
        }
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
                    // Si la fila no cambió mientras subía, queda limpia; si cambió, conserva "sucia" pero con la nueva base
                    val unchanged = Content.same(now, pushed) && now.deleted == pushed.deleted
                    put(now.copy(rev = r.rev, dirty = !unchanged)); done++
                }
                PushStatus.CONFLICT -> {
                    val server = r.server ?: throw RemoteException(RemoteException.Kind.SERVER, "Conflicto sin versión del servidor")
                    val root = current.rootOf(now) ?: now.key
                    newConflicts.getOrPut(root) { mutableListOf() } += ConflictItem(now.table, now.id, now, server)
                }
                PushStatus.BLOCKED -> {}     // su agregado entró en conflicto en otra fila; sigue sucia y congelada
                else -> throw RemoteException(RemoteException.Kind.SERVER, "Estado de subida desconocido: ${r.status}")
            }
        }
        for ((root, items) in newConflicts) saveConflictMerged(root, items, labelOf(current, root), reasonOf(items), complete = false)
        if (newConflicts.isNotEmpty()) setMeta(meta().copy(cursor = 0))     // nueva descarga completa: el grupo recogerá TODAS las versiones del servidor
        return done
    }

    // ───────────── descarga ─────────────

    private suspend fun pullAll(): Int {
        var applied = 0
        var cursor = store.transaction { meta().cursor }
        var guard = 0
        do {
            val page = remote.pull(cursor, pageSize)
            applied += store.transaction {
                // El servidor tiene MENOS historial que mi cursor (p. ej. se restauró una copia): todo lo local debe poder volver a subirse.
                // La base pasa a 0, así que nada se pisa: lo idéntico se reconoce y lo distinto sale como conflicto.
                if (page.reset) records().filter { !it.dirty || it.rev != 0L }.forEach { put(it.copy(rev = 0, dirty = true)) }
                val n = applyPage(page.changes)
                setMeta(meta().copy(cursor = page.cursor))
                n
            }
            if (page.hasMore && page.cursor <= cursor && !page.reset) throw RemoteException(RemoteException.Kind.SERVER, "Cursor sin avance")
            cursor = page.cursor
        } while (page.hasMore && guard++ < 100_000)
        store.transaction { conflicts().filter { !it.complete }.forEach { saveConflict(it.copy(complete = true)) } }   // descarga completa terminada
        return applied
    }

    private suspend fun SyncTx.applyPage(changes: List<RemoteRecord>): Int {
        val all = records()
        var local = all.associateBy { it.key }.toMutableMap()
        val frozen = conflicts().map { it.root }.toMutableSet()
        fun rootOfRemote(c: RemoteRecord): Key {
            var key = Key(c.table, c.id); var data = c.data; var guard = 0
            while (key.table in Tables.PARENT && guard++ < 4) {
                val (pt, f) = Tables.PARENT.getValue(key.table)
                val pid = (data[f] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return key
                key = Key(pt, pid)
                data = local[key]?.data ?: pages[key]?.data ?: return key
            }
            return key
        }
        pages = changes.associateBy { Key(it.table, it.id) }
        // 1ª pasada: detectar conflictos en esta página (para congelar el agregado ANTES de aplicar nada de él)
        val conflicting = mutableMapOf<Key, MutableList<ConflictItem>>()
        for (c in changes) {
            val mine = local[Key(c.table, c.id)] ?: continue
            if (mine.dirty && !(Content.same(mine, c.toRec()) )) conflicting.getOrPut(rootOfRemote(c)) { mutableListOf() } += ConflictItem(mine.table, mine.id, mine, c)
        }
        frozen += conflicting.keys
        var n = 0
        val held = mutableMapOf<Key, MutableList<ConflictItem>>()
        for (c in changes) {
            val k = Key(c.table, c.id)
            val root = rootOfRemote(c)
            val mine = local[k]
            if (root in frozen) {
                // agregado congelado: la versión remota se guarda en el conflicto, no se aplica
                if (conflicting[root]?.any { it.table == c.table && it.id == c.id } != true) held.getOrPut(root) { mutableListOf() } += ConflictItem(c.table, c.id, mine, c)
                continue
            }
            val incoming = c.toRec(dirty = false)
            when {
                mine == null -> { put(incoming); local[k] = incoming; n++ }
                !mine.dirty -> if (c.rev >= mine.rev) { put(incoming); local[k] = incoming; n++ }
                else -> { put(mine.copy(rev = c.rev, dirty = false)); local[k] = mine.copy(rev = c.rev, dirty = false); n++ }   // idéntica: adopta la revisión
            }
        }
        val idx = RecIndex(local.values)
        for ((root, items) in conflicting) saveConflictMerged(root, items + held.remove(root).orEmpty(), labelOf(idx, root), reasonOf(items), complete = false)
        for ((root, items) in held) saveConflictMerged(root, items, labelOf(idx, root), ConflictReason.BOTH_CHANGED, complete = false)
        return n
    }

    private var pages: Map<Key, RemoteRecord> = emptyMap()

    // ───────────── conflictos ─────────────

    private fun labelOf(idx: RecIndex, root: Key): String = idx.byKey[root]?.str("name") ?: root.id
    private fun reasonOf(items: List<ConflictItem>): ConflictReason = when {
        items.any { it.remote?.deletedAt != null && it.local?.deleted == false } -> ConflictReason.DELETED_REMOTELY
        items.any { it.local?.deleted == true && it.remote?.deletedAt == null } -> ConflictReason.DELETED_LOCALLY
        else -> ConflictReason.BOTH_CHANGED
    }

    private suspend fun SyncTx.saveConflictMerged(root: Key, items: List<ConflictItem>, label: String, reason: ConflictReason, complete: Boolean) {
        val existing = conflicts().firstOrNull { it.root == root }
        // si ya había una versión del servidor de la fila, se conserva la más reciente que llegó (la de esta tanda)
        val merged = (existing?.items.orEmpty() + items).associateBy { Key(it.table, it.id) }.values.toList()
        saveConflict(ConflictGroup(root, label, merged, existing?.reason ?: reason, existing?.detectedAt ?: clock(), complete = complete && (existing?.complete ?: true)))
    }
}
