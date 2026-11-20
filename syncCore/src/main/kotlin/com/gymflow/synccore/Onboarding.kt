package com.gymflow.synccore

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Copia recuperable de TODAS las filas locales (incluidos borrados lógicos y estado de sincronización). */
object BackupFormat {
    fun serialize(recs: Collection<Rec>): ByteArray {
        val rows = JsonArray(recs.sortedWith(compareBy({ it.table }, { it.id })).map {
            buildJsonObject {
                put("table", it.table); put("id", it.id); put("rev", it.rev); put("dirty", it.dirty)
                it.deletedAt?.let { d -> put("deletedAt", d) }
                put("data", it.data)
            }
        })
        return JsonObject(mapOf("format" to JsonPrimitive("gymflow-raw-backup"), "version" to JsonPrimitive(1), "rows" to rows)).toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): List<Rec> {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject
        require((o["format"] as JsonPrimitive).content == "gymflow-raw-backup") { "no es una copia de GymFlow" }
        return (o["rows"] as JsonArray).map {
            val r = it as JsonObject
            Rec(
                (r["table"] as JsonPrimitive).content, (r["id"] as JsonPrimitive).content,
                (r["deletedAt"] as? JsonPrimitive)?.content?.toLong(), r["data"] as JsonObject,
                (r["rev"] as JsonPrimitive).content.toLong(), (r["dirty"] as JsonPrimitive).content.toBoolean(),
            )
        }
    }

    /** Prueba de que la copia escrita (releída del almacenamiento) coincide EXACTAMENTE con el estado local. */
    fun prove(written: ByteArray, local: Collection<Rec>): BackupProof? {
        val back = runCatching { parse(written) }.getOrNull() ?: return null
        if (back.size != local.size) return null
        val same = back.associateBy { it.key }.let { m -> local.all { l -> m[l.key]?.let { b -> b.data == l.data && b.deletedAt == l.deletedAt && b.rev == l.rev && b.dirty == l.dirty } == true } }
        return if (same) BackupProof(local.size, Content.fingerprint(local), Content.sha256(written)) else null
    }
}

data class BackupProof(val rows: Int, val localFingerprint: String, val fileSha256: String)

enum class OAction { UPLOAD, DOWNLOAD, ADOPT, HARMONIZE, CONFLICT }

data class OItem(val root: Key, val label: String, val action: OAction, val rows: Int, val note: String? = null)

class OnboardingPlan internal constructor(
    /** Cuenta+servidor para los que se preparó (H1). */
    val ownerIdentity: String,
    val localFingerprint: String,
    val remoteCursor: Long,
    val items: List<OItem>,
    val conflicts: List<ConflictGroup>,
    val warnings: List<String>,
    internal val uploads: List<Rec>,
    internal val downloads: List<RemoteRecord>,
    internal val adopts: List<Pair<Rec, RemoteRecord>>,
    internal val removals: List<Key>,
    internal val remoteAll: Map<Key, RemoteRecord>,
    val localRows: Int,
) {
    fun count(a: OAction) = items.count { it.action == a }
}

class OnboardingBlockedException(message: String) : Exception(message)

data class OnboardingResult(val uploaded: Int, val downloaded: Int, val adopted: Int, val harmonizedRemoved: Int, val conflicts: Int)

/**
 * Incorporación inicial de un dispositivo al servidor. Nunca asume que el servidor tiene la copia correcta ni que la local lo es:
 *  prepare(): SOLO LEE (local y remoto) y compara agregado a agregado → plan con vista previa. No escribe en ningún sitio.
 *  apply():   exige una copia recuperable verificada y que nada haya cambiado; aplica todo en UNA transacción y verifica el resultado.
 */
object Onboarding {
    suspend fun prepare(store: SyncStore, remote: RemoteApi, pageSize: Int = 500): OnboardingPlan {
        val identity = store.boundIdentity
        store.requireSame(identity)
        val local = store.transaction { store.requireSame(identity); records() }
        val remoteRecs = mutableListOf<RemoteRecord>()
        var cursor = 0L; var guard = 0
        do {
            val page = remote.pull(cursor, pageSize)
            remoteRecs += page.changes
            if (page.hasMore && page.cursor <= cursor) throw RemoteException(RemoteException.Kind.SERVER, "Cursor sin avance")
            cursor = page.cursor
        } while (page.hasMore && guard++ < 100_000)
        val remoteLatest = remoteRecs.groupBy { Key(it.table, it.id) }.mapValues { (_, v) -> v.maxBy { it.version } }   // por si una fila aparece en dos páginas
        store.requireSame(identity)                                  // la cuenta no cambió mientras se leía el servidor
        return compare(local, remoteLatest.values.toList(), cursor, identity)
    }

    internal fun compare(local: List<Rec>, remote: List<RemoteRecord>, cursor: Long, identity: String): OnboardingPlan {
        val lIdx = RecIndex(local)
        val remoteRecs = remote.map { it.toRec() }
        val rIdx = RecIndex(remoteRecs)
        val remoteByKey = remote.associateBy { Key(it.table, it.id) }
        val roots = linkedSetOf<Key>()
        local.forEach { roots += lIdx.rootOf(it) ?: it.key }
        remoteRecs.forEach { roots += rIdx.rootOf(it) ?: it.key }

        val items = mutableListOf<OItem>(); val conflicts = mutableListOf<ConflictGroup>()
        val uploads = mutableListOf<Rec>(); val downloads = mutableListOf<RemoteRecord>()
        val adopts = mutableListOf<Pair<Rec, RemoteRecord>>(); val removals = mutableListOf<Key>()
        val warnings = mutableListOf<String>()

        for (root in roots.sortedBy { Tables.ALL.indexOf(it.table) }) {
            val la = lIdx.aggregate(root).ifEmpty { local.filter { (lIdx.rootOf(it) ?: it.key) == root } }
            val ra = rIdx.aggregate(root).ifEmpty { remoteRecs.filter { (rIdx.rootOf(it) ?: it.key) == root } }
            val label = (la.firstOrNull { it.key == root } ?: ra.firstOrNull { it.key == root })?.str("name") ?: root.id
            when {
                ra.isEmpty() -> { uploads += la; items += OItem(root, label, OAction.UPLOAD, la.size) }
                la.isEmpty() -> { downloads += ra.map { remoteByKey.getValue(it.key) }; items += OItem(root, label, OAction.DOWNLOAD, ra.size) }
                else -> {
                    val lm = la.associateBy { it.key }; val rm = ra.associateBy { it.key }
                    val identical = lm.keys == rm.keys && lm.all { (k, l) -> Content.same(l, rm.getValue(k)) }
                    if (identical) {
                        la.forEach { adopts += it to remoteByKey.getValue(it.key) }
                        items += OItem(root, label, OAction.ADOPT, la.size)
                    } else if (harmonizable(root, lm, rm, lIdx, rIdx)) {
                        // mismo contenido de negocio, distinto id de series (p. ej. rutina importada de una copia JSON): se queda la versión del servidor
                        val onlyLocal = lm.filterKeys { it !in rm }.keys
                        removals += onlyLocal
                        la.filter { it.key in rm }.forEach { adopts += it to remoteByKey.getValue(it.key) }
                        downloads += rm.keys.filter { it !in lm }.map { remoteByKey.getValue(it) }
                        items += OItem(root, label, OAction.HARMONIZE, la.size, "mismo contenido; se unifican ${onlyLocal.size} fila(s) local(es) con ids distintos")
                    } else {
                        val keys = (lm.keys + rm.keys).sortedBy { Tables.ALL.indexOf(it.table) }
                        val cItems = keys.map { ConflictItem(it.table, it.id, lm[it], remoteByKey[it]) }
                        val reason = when {
                            lm[root]?.deleted == true && rm[root]?.deleted == false -> ConflictReason.DELETED_LOCALLY
                            lm[root]?.deleted == false && rm[root]?.deleted == true -> ConflictReason.DELETED_REMOTELY
                            else -> ConflictReason.FIRST_SYNC
                        }
                        conflicts += ConflictGroup(root, label, cItems, reason, detectedAt = 0, complete = true)
                        items += OItem(root, label, OAction.CONFLICT, keys.size)
                    }
                }
            }
        }
        val names = local.filter { it.table == Tables.ROUTINES && !it.deleted }.map { it.str("name")?.trim()?.lowercase() } +
            remoteRecs.filter { it.table == Tables.ROUTINES && !it.deleted }.map { it.str("name")?.trim()?.lowercase() }
        val dupNames = names.filterNotNull().groupingBy { it }.eachCount().count { it.value > 1 }
        val ids = (local.filter { it.table == Tables.ROUTINES && !it.deleted }.map { it.id } + remoteRecs.filter { it.table == Tables.ROUTINES && !it.deleted }.map { it.id }).toSet()
        if (ids.size > names.filterNotNull().toSet().size) warnings += "Hay rutinas con el mismo nombre y distinto id ($dupNames nombre(s) repetido(s)): se conservan todas, sin fusionarlas automáticamente."
        return OnboardingPlan(identity, Content.fingerprint(local), cursor, items, conflicts, warnings, uploads, downloads, adopts, removals, remoteByKey, local.size)
    }

    private fun harmonizable(root: Key, lm: Map<Key, Rec>, rm: Map<Key, Rec>, lIdx: RecIndex, rIdx: RecIndex): Boolean {
        val kind = when (root.table) { Tables.ROUTINES -> Kind.ROUTINE; Tables.WORKOUTS -> Kind.WORKOUT; else -> return false }
        val mine = AggregateView.of(kind, root, lIdx) ?: return false
        val theirs = AggregateView.of(kind, root, rIdx) ?: return false
        if (!AggregateView.equal(mine, theirs)) return false
        // todo lo que difiere debe ser SOLO filas de series sin id compartido y que nunca se subieron (rev = 0)
        val onlyLocal = lm.filterKeys { it !in rm }
        if (onlyLocal.any { (k, r) -> k.table != Tables.ROUTINE_SETS || r.rev != 0L }) return false
        val sharedDiffer = lm.keys.intersect(rm.keys).any { !Content.same(lm.getValue(it), rm.getValue(it)) }
        return !sharedDiffer
    }

    suspend fun apply(store: SyncStore, plan: OnboardingPlan, backup: BackupProof?): OnboardingResult {
        store.requireSame(plan.ownerIdentity)                        // H1
        if (backup == null) throw OnboardingBlockedException("No hay una copia recuperable verificada: no se puede incorporar este dispositivo.")
        if (backup.localFingerprint != plan.localFingerprint || backup.rows != plan.localRows)
            throw OnboardingBlockedException("La copia recuperable no corresponde al estado analizado.")
        return SyncLock.mutex.withLock {
            store.transaction {
                store.requireSame(plan.ownerIdentity)
                val before = records()
                if (Content.fingerprint(before) != plan.localFingerprint) throw StaleImportPlanException()
                if (meta().onboarded) throw OnboardingBlockedException("Este dispositivo ya está incorporado.")
                val beforeIdx = before.associateBy { it.key }
                plan.removals.forEach { k -> remove(k.table, k.id) }
                plan.downloads.forEach { put(it.toRec(dirty = false)) }
                plan.adopts.forEach { (l, r) -> put(l.copy(rev = r.rev, dirty = false)) }
                plan.uploads.forEach { put(it.copy(rev = 0, dirty = true)) }
                plan.conflicts.forEach { saveConflict(it) }
                setMeta(meta().copy(onboarded = true, cursor = plan.remoteCursor))

                // Verificación dentro de la misma transacción
                val after = RecIndex(records())
                val conflicted = plan.conflicts.flatMap { g -> g.items.map { Key(it.table, it.id) } }.toSet()
                for ((k, r) in plan.remoteAll) {
                    if (k in conflicted) continue
                    val mine = after.byKey[k] ?: throw ImportIntegrityException("Falta ${k.table}/${k.id} del servidor")
                    if (!Content.same(mine, r.toRec())) throw ImportIntegrityException("Contenido distinto en ${k.table}/${k.id}")
                }
                for ((k, old) in beforeIdx) {
                    if (k in plan.removals) continue
                    val now = after.byKey[k] ?: throw ImportIntegrityException("Se perdió ${k.table}/${k.id}")
                    if (k !in conflicted && !plan.remoteAll.containsKey(k) && !Content.same(now, old)) throw ImportIntegrityException("Cambió ${k.table}/${k.id}")
                }
                for (r in after.byKey.values) Tables.PARENT[r.table]?.let { (pt, f) -> if (after[pt, r.str(f) ?: ""] == null) throw ImportIntegrityException("Padre ausente de ${r.table}/${r.id}") }
                store.requireSame(plan.ownerIdentity)                        // H1: justo antes de confirmar
                OnboardingResult(plan.uploads.size, plan.downloads.size, plan.adopts.size, plan.removals.size, plan.conflicts.size)
            }
        }
    }
}

/** Resolución explícita de conflictos de agregado. Lo descartado se archiva ANTES de descartarlo (recuperable); nada se pierde. */
object ConflictResolver {
    class NotResolvableException(message: String) : Exception(message)

    /** Hubo ediciones locales DESPUÉS de detectarse el conflicto: hay que confirmar de nuevo sabiendo que se archivan. */
    class LocalChangedSinceConflictException(val changedKeys: List<Key>) :
        Exception("Has editado esto después de que se detectara el conflicto. Confirma de nuevo: tu última versión quedará archivada.")

    private suspend fun SyncTx.group(root: Key): ConflictGroup {
        val g = conflicts().firstOrNull { it.root == root } ?: throw NotResolvableException("No hay conflicto abierto")
        if (!g.complete) throw NotResolvableException("Aún no se han descargado todas las versiones del servidor: sincroniza de nuevo")
        return g
    }

    /** Filas cuyo estado local ACTUAL difiere del que se guardó al detectar el conflicto (o filas nuevas pendientes añadidas después). */
    private fun editedSince(g: ConflictGroup, cur: RecIndex): List<Key> {
        val agg = cur.aggregate(g.root).associateBy { it.key }
        val then = g.items.associateBy { Key(it.table, it.id) }
        val out = mutableListOf<Key>()
        for ((k, item) in then) {
            val now = agg[k]; val before = item.local
            if (before == null && now != null) out += k
            else if (before != null && (now == null || !Content.same(now, before))) out += k
        }
        for ((k, now) in agg) if (k !in then && now.dirty) out += k
        return out
    }

    /** Estado LOCAL ACTUAL completo del agregado, tal como está en este momento, junto con las versiones del servidor del conflicto. */
    private fun currentSnapshot(g: ConflictGroup, cur: RecIndex): ConflictGroup {
        val byKey = g.items.associateBy { Key(it.table, it.id) }
        val keys = (byKey.keys + cur.aggregate(g.root).map { it.key }).toList()
        return g.copy(items = keys.map { k -> ConflictItem(k.table, k.id, cur.byKey[k], byKey[k]?.remote) })
    }

    suspend fun keepLocal(store: SyncStore, root: Key, now: Long) = SyncLock.mutex.withLock {
        store.transaction {
            store.requireSame(store.boundIdentity)
            val g = group(root)
            val cur = RecIndex(records())
            for (item in g.items.associateBy { Key(it.table, it.id) }.values) {
                val l = cur.byKey[Key(item.table, item.id)]
                val r = item.remote
                when {
                    l != null && r != null -> put(l.copy(rev = r.rev, dirty = true))
                    l != null -> put(l.copy(rev = 0, dirty = true))
                    r != null && r.deletedAt == null -> put(Rec(r.table, r.id, now, tombstone(r, now), r.rev, dirty = true))   // solo en el servidor: se retira para converger a lo local
                }
            }
            archive(g, "KEEP_LOCAL", now); removeConflict(root)
            store.requireSame(store.boundIdentity)                 // N-04: también justo antes de confirmar       // lo descartado aquí es la versión del servidor: queda en el grupo archivado
        }
    }

    /**
     * Usar la versión del servidor. ANTES de sustituir nada se archiva el estado local ACTUAL completo del agregado (no el de cuando se
     * detectó el conflicto) y, si difiere, también el del momento del conflicto. Si hubo ediciones locales posteriores al conflicto,
     * exige [acknowledgeLocalEdits] = true (una nueva confirmación expresa del usuario).
     */
    suspend fun useRemote(store: SyncStore, root: Key, now: Long, acknowledgeLocalEdits: Boolean = false) = SyncLock.mutex.withLock {
        store.transaction {
            store.requireSame(store.boundIdentity)
            val g = group(root)
            val cur = RecIndex(records())
            val edited = editedSince(g, cur)
            if (edited.isNotEmpty() && !acknowledgeLocalEdits) throw LocalChangedSinceConflictException(edited)
            // 1) archivo del estado local ACTUAL (con todas las filas del agregado) + versiones del servidor del conflicto
            archive(currentSnapshot(g, cur), "USE_REMOTE", now)
            // 2) si además se editó después del conflicto, también queda la versión «de cuando se detectó»
            if (edited.isNotEmpty()) archive(g, "USE_REMOTE_CONFLICT_TIME_LOCAL", now)
            // 3) ahora sí se sustituye
            val inItems = g.items.map { Key(it.table, it.id) }.toSet()
            for (item in g.items.associateBy { Key(it.table, it.id) }.values) {
                val r = item.remote
                if (r != null) put(r.toRec(dirty = false))
                else cur.byKey[Key(item.table, item.id)]?.let { remove(it.table, it.id) }       // solo existía localmente (ya archivado)
            }
            cur.aggregate(root).filter { it.key !in inItems }.forEach { remove(it.table, it.id) }  // filas locales del agregado que el servidor no tiene (archivadas)
            removeConflict(root)
            store.requireSame(store.boundIdentity)                 // N-04: también justo antes de confirmar
        }
    }

    private fun tombstone(r: RemoteRecord, now: Long): JsonObject =
        JsonObject(r.data + mapOf("deletedAt" to JsonPrimitive(now), "updatedAt" to JsonPrimitive(now)))
}

/** Recuperación de lo archivado al resolver un conflicto: las filas locales tal como estaban, listas para volver a escribirse. */
object ArchiveRecovery {
    fun localRows(group: ConflictGroup): List<Rec> = group.items.mapNotNull { it.local }
    fun remoteRows(group: ConflictGroup): List<RemoteRecord> = group.items.mapNotNull { it.remote }
}
