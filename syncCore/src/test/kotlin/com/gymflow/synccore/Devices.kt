package com.gymflow.synccore

/** Un "teléfono" sintético: su base local, su cliente de sincronización y su enlace (falsable) con el servidor. */
class Device(val name: String, initial: List<Rec>, val server: FakeServer, now: Long = BASE_MS + 20_000_000, pageSize: Int = 500, pushBatch: Int = 200) {
    val store = MemStore(initial)
    val sync = SyncView(store)
    val runner = SyncRunner(sync, server, { now }, pageSize, pushBatch)
    val rows get() = store.rows

    suspend fun plan() = Onboarding.prepare(sync, server)

    /** Copia recuperable escrita (en memoria) y verificada releyéndola, como hará la app con un archivo. */
    suspend fun backup(): BackupProof? {
        val local = sync.transaction { records() }
        val bytes = BackupFormat.serialize(local)
        return BackupFormat.prove(bytes, local)
    }

    suspend fun onboard(prepared: OnboardingPlan? = null) = Onboarding.apply(sync, prepared ?: plan(), backup())
    suspend fun run() = runner.run()

    /** Edita una fila local como lo haría la app: contenido nuevo, updatedAt nuevo y marcada sucia. */
    suspend fun edit(table: String, id: String, field: String, v: kotlinx.serialization.json.JsonElement, at: Long = BASE_MS + 30_000_000) {
        sync.transaction { val r = records().first { it.key == Key(table, id) }; put(r.edited(field, v, at)) }
    }
    suspend fun insert(recs: List<Rec>) = sync.transaction { recs.forEach { put(it) } }
    suspend fun softDelete(table: String, id: String, at: Long = BASE_MS + 40_000_000) {
        sync.transaction { val r = records().first { it.key == Key(table, id) }; put(r.softDeleted(at)) }
    }
    fun contentMap() = rows.mapValues { Content.key(it.value) }
    fun live(table: String) = rows.values.filter { it.table == table && !it.deleted }
}

fun assertNoOrphans(rows: Collection<Rec>) {
    val idx = RecIndex(rows)
    rows.forEach { r -> Tables.PARENT[r.table]?.let { (pt, f) -> kotlin.test.assertNotNull(idx[pt, r.str(f) ?: ""], "huérfano ${r.table}/${r.id}") } }
}
