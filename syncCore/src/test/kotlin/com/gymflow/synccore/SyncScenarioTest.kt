package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class SyncScenarioTest {
    private val state = FakeServerState()
    private fun server(uid: String = UID) = FakeServer(state, uid)

    // ───────────── gate de incorporación ─────────────

    @Test fun `sin incorporacion no se hace ninguna llamada de red`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1), s)
        assertEquals(SyncOutcome.OnboardingRequired, d.run())
        assertEquals(0, s.pushCalls); assertEquals(0, s.pullCalls); assertEquals(0, state.rowCount(UID))
    }

    @Test fun `la incorporacion exige una copia recuperable verificada`() = runBlocking {
        val d = Device("A", workoutRecs(1), server())
        val plan = d.plan()
        val before = d.rows.toMap()
        assertFailsWith<OnboardingBlockedException> { Onboarding.apply(d.sync, plan, null) }
        assertFailsWith<OnboardingBlockedException> { Onboarding.apply(d.sync, plan, BackupProof(1, "otra-huella", "x")) }
        assertEquals(before, d.rows); assertEquals(false, d.store.metaState.onboarded)
        assertNull(BackupFormat.prove(ByteArray(10), d.rows.values.toList()))                      // una copia ilegible no sirve
        assertNull(BackupFormat.prove(BackupFormat.serialize(d.rows.values.drop(1)), d.rows.values.toList()))   // ni una incompleta
    }

    @Test fun `servidor vacio sube todo y la sincronizacion es idempotente`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1) + routineRecs(1) + achievementRec("a"), s)
        val r = d.onboard()
        assertEquals(d.rows.size, r.uploaded); assertEquals(0, s.pushCalls)                        // preparar y aplicar no suben nada
        val out = d.run() as SyncOutcome.Success
        assertEquals(d.rows.size, out.pushed); assertEquals(d.rows.size, state.rowCount(UID))
        assertTrue(d.rows.values.none { it.dirty } && d.rows.values.all { it.rev == 1L })
        val again = d.run() as SyncOutcome.Success
        assertEquals(0, again.pushed)                                                              // operación repetida: nada nuevo
        assertEquals(d.rows.size, state.rowCount(UID))
    }

    // ───────────── Samsung ↔ POCO (datos sintéticos equivalentes) ─────────────

    private fun samsung(): List<Rec> = (0..8).flatMap { workoutRecs(it) } + (0..4).flatMap { routineRecs(it, name = if (it == 0) "Push" else "Rutina S$it", setTag = "samsung") } +
        listOf(achievementRec("first_workout"), achievementRec("ten_workouts"), achievementRec("pr_bench"))
    private fun poco(): List<Rec> = (0..1).flatMap { workoutRecs(it) } + (10..11).flatMap { routineRecs(it, name = if (it == 10) "Push" else "Rutina P$it", setTag = "poco") }

    @Test fun `Samsung y POCO convergen a los nueve entrenamientos sin duplicados ni perdidas`() = runBlocking {
        val sam = Device("Samsung", samsung(), server()); val poc = Device("POCO", poco(), server())
        val samBefore = sam.rows.toMap(); val pocBefore = poc.rows.toMap()

        sam.onboard(); assertTrue(sam.run() is SyncOutcome.Success)
        val plan = poc.plan()                                   // vista previa en el POCO: SOLO lectura
        assertEquals(2, plan.items.count { it.action == OAction.ADOPT && it.root.table == Tables.WORKOUTS })    // los dos entrenos antiguos coinciden
        assertEquals(7, plan.items.count { it.action == OAction.DOWNLOAD && it.root.table == Tables.WORKOUTS })
        assertEquals(5 , plan.items.count { it.action == OAction.DOWNLOAD && it.root.table == Tables.ROUTINES })
        assertEquals(2, plan.items.count { it.action == OAction.UPLOAD && it.root.table == Tables.ROUTINES })
        assertEquals(3, plan.items.count { it.action == OAction.DOWNLOAD && it.root.table == Tables.ACHIEVEMENTS })
        assertEquals(0, plan.count(OAction.CONFLICT))
        assertTrue(plan.warnings.any { it.contains("mismo nombre") })        // "Push" existe en ambos con distinto id: se avisa, NO se fusiona
        assertEquals(pocBefore, poc.rows.toMap())                          // la vista previa no modificó nada

        poc.onboard(plan); assertTrue(poc.run() is SyncOutcome.Success)
        assertTrue(sam.run() is SyncOutcome.Success)                       // el Samsung recibe las dos rutinas del POCO

        for (d in listOf(sam, poc)) {
            val workouts = d.live(Tables.WORKOUTS)
            assertEquals(9, workouts.size); assertEquals(9, workouts.map { it.id }.toSet().size)
            assertEquals(7, d.live(Tables.ROUTINES).size)                   // 5 + 2: ninguna fusionada por nombre
            assertEquals(2, d.live(Tables.ROUTINES).count { it.str("name") == "Push" })
            assertEquals(3, d.live(Tables.ACHIEVEMENTS).size)
            assertNoOrphans(d.rows.values)
            assertTrue(d.store.conflictGroups.isEmpty()); assertTrue(d.rows.values.none { it.dirty })
        }
        assertEquals(sam.contentMap(), poc.contentMap())                    // mismo CONTENIDO fila a fila, no solo recuentos
        samBefore.forEach { (k, v) -> assertTrue(Content.same(v, sam.rows.getValue(k)), "Samsung perdió ${k.table}/${k.id}") }
        pocBefore.forEach { (k, v) -> assertTrue(Content.same(v, poc.rows.getValue(k)), "POCO perdió ${k.table}/${k.id}") }
        assertEquals(sam.rows.size, state.rowCount(UID))                    // el servidor tiene exactamente la unión, sin duplicados
        // una segunda ronda no cambia nada
        val snap = poc.contentMap(); poc.run(); sam.run(); assertEquals(snap, poc.contentMap())
    }

    @Test fun `Samsung y POCO tambien convergen si el POCO importa primero el JSON del Samsung`() = runBlocking {
        val sam = Device("Samsung", samsung(), server()); val poc = Device("POCO", poco(), server())
        sam.onboard(); sam.run()
        // 1) importación JSON en el POCO (copia del Samsung con el formato real de exportación)
        val parsed = ExportParser.parse(exportJson(sam.rows.values.toList()).toByteArray(), UID)
        val p = ImportPlanner.plan(parsed, poc.rows.values.toList(), UID)
        assertEquals(7, p.items.count { it.kind == Kind.WORKOUT && it.status == ItemStatus.NEW }); assertEquals(2, p.items.count { it.kind == Kind.WORKOUT && it.status == ItemStatus.IDENTICAL })
        assertEquals(0, p.conflictCount)
        ImportExecutor.apply(poc.store, p)
        assertEquals(9, poc.live(Tables.WORKOUTS).size)
        // 2) después se incorpora: el servidor ya tiene lo del Samsung; lo importado coincide y los ids de series de rutina distintos se unifican
        val plan = poc.plan()
        assertEquals(0, plan.count(OAction.CONFLICT), plan.items.filter { it.action == OAction.CONFLICT }.toString())
        assertTrue(plan.count(OAction.HARMONIZE) >= 5)                                     // las 5 rutinas importadas traen ids de serie derivados
        poc.onboard(plan); poc.run(); sam.run()
        for (d in listOf(sam, poc)) {
            assertEquals(9, d.live(Tables.WORKOUTS).size); assertEquals(7, d.live(Tables.ROUTINES).size); assertEquals(3, d.live(Tables.ACHIEVEMENTS).size)
            assertEquals(5 * 2 * 2 + 2 * 2 * 2, d.live(Tables.ROUTINE_SETS).size, "series de rutina duplicadas tras importar + sincronizar")
            assertNoOrphans(d.rows.values)
        }
        assertEquals(sam.contentMap(), poc.contentMap())
    }

    // ───────────── dos dispositivos offline ─────────────

    private suspend fun pair(a: List<Rec> = emptyList(), b: List<Rec> = emptyList()): Pair<Device, Device> {
        val da = Device("A", a, server()); val db = Device("B", b, server())
        da.onboard(); da.run(); db.onboard(); db.run(); da.run()
        return da to db
    }

    @Test fun `creaciones concurrentes offline se unen sin perder nada`() = runBlocking {
        val (a, b) = pair(workoutRecs(0), workoutRecs(0))
        a.insert(workoutRecs(1)); b.insert(workoutRecs(2) + routineRecs(5))
        a.run(); b.run(); a.run()
        for (d in listOf(a, b)) { assertEquals(3, d.live(Tables.WORKOUTS).size); assertEquals(1, d.live(Tables.ROUTINES).size) }
        assertEquals(a.contentMap(), b.contentMap())
    }

    @Test fun `edicion concurrente del mismo registro es un conflicto explicito sin perdida y sin ganar por fecha`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val rid = uuid("r1")
        // B edita DESPUÉS (fecha mayor) pero A sube primero: el reloj no decide
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Nombre de A"), at = BASE_MS + 1000)
        b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Nombre de B"), at = BASE_MS + 999_000_000)
        a.run(); val out = b.run() as SyncOutcome.Success
        assertEquals(1, out.openConflicts)
        val g = b.store.conflictGroups.values.single()
        assertEquals(Key(Tables.ROUTINES, rid), g.root); assertTrue(g.complete)                // tras la descarga completa queda resoluble
        assertEquals("Nombre de B", b.rows.getValue(g.root).str("name"))                       // lo local sigue intacto
        assertEquals("Nombre de A", g.items.first { it.table == Tables.ROUTINES }.remote!!.data["name"].let { (it as JsonPrimitive).content })
        assertEquals("Nombre de A", a.snapshotServerName(rid))                                 // el servidor no se sobrescribió
        // el conflicto sobrevive a reinicios (está en la base) y no se duplica
        b.run(); assertEquals(1, b.store.conflictGroups.size)

        ConflictResolver.keepLocal(b.sync, g.root, now = BASE_MS)
        assertEquals(0, b.store.conflictGroups.size); assertEquals(1, b.store.archived.size)
        b.run(); a.run()
        assertEquals("Nombre de B", a.rows.getValue(g.root).str("name")); assertEquals(a.contentMap(), b.contentMap())
    }

    @Test fun `usar la version del servidor descarta lo local solo despues de archivarlo`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Nombre de A"))
        b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Nombre de B"))
        a.run(); b.run()
        ConflictResolver.useRemote(b.sync, Key(Tables.ROUTINES, rid), now = BASE_MS)
        assertEquals("Nombre de A", b.rows.getValue(Key(Tables.ROUTINES, rid)).str("name"))
        val archived = b.store.archived.single()
        assertEquals("USE_REMOTE", archived.second)
        assertEquals("Nombre de B", archived.first.items.first { it.table == Tables.ROUTINES }.local!!.str("name"))   // la versión local queda recuperable
        b.run(); a.run(); assertEquals(a.contentMap(), b.contentMap())
    }

    @Test fun `un conflicto no se puede resolver hasta tener todas las versiones del servidor`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("A")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("B"))
        a.run()
        b.server.failPullAfterPages = 0           // la subida detecta el conflicto, pero la descarga completa se corta
        val out = b.run(); assertTrue(out is SyncOutcome.Failed)
        val g = b.store.conflictGroups.values.single(); assertEquals(false, g.complete)
        assertFailsWith<ConflictResolver.NotResolvableException> { ConflictResolver.keepLocal(b.sync, g.root, 1) }
        b.run()                                    // reintento: se completa
        assertTrue(b.store.conflictGroups.values.single().complete)
        ConflictResolver.keepLocal(b.sync, g.root, 1)
    }

    @Test fun `un conflicto congela el agregado entero y no mezcla versiones`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val rid = uuid("r1"); val eid = uuid("re1-0")
        a.edit(Tables.ROUTINE_EXERCISES, eid, "notes", JsonPrimitive("nota de A"))
        b.edit(Tables.ROUTINE_EXERCISES, eid, "notes", JsonPrimitive("nota de B"))
        b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Renombrada en B"))     // otra fila de la MISMA rutina, sin conflicto propio
        a.run(); b.run()
        assertEquals(Key(Tables.ROUTINES, rid), b.store.conflictGroups.keys.single())
        val serverName = a.snapshotServerName(rid)
        assertNotEquals("Renombrada en B", serverName)                              // la fila "sin conflicto" NO se subió suelta: el agregado va entero o no va
    }

    // ───────────── borrados ─────────────

    @Test fun `un borrado se propaga como tombstone y no resucita`() = runBlocking {
        val (a, b) = pair(workoutRecs(1), workoutRecs(1))
        a.softDelete(Tables.WORKOUTS, uuid("w1")); a.run(); b.run()
        assertTrue(b.rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).deleted)
        assertEquals(0, b.live(Tables.WORKOUTS).size)
        b.run(); a.run()                                                           // rondas posteriores no lo resucitan
        assertTrue(a.rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).deleted)
        assertTrue(state.user(UID).rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).deletedAt != null)
    }

    @Test fun `editar offline algo que otro dispositivo borro es un conflicto y no lo resucita`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val rid = uuid("r1")
        a.softDelete(Tables.ROUTINES, rid); a.run()
        b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("sigo editando"))
        val out = b.run() as SyncOutcome.Success
        assertEquals(1, out.openConflicts)
        assertEquals(ConflictReason.DELETED_REMOTELY, b.store.conflictGroups.values.single().reason)
        assertTrue(state.user(UID).rows.getValue(Key(Tables.ROUTINES, rid)).deletedAt != null)   // el servidor sigue borrado
        assertEquals("sigo editando", b.rows.getValue(Key(Tables.ROUTINES, rid)).str("name"))     // y lo local, intacto
    }

    // ───────────── fallos de red, reintentos, reinicios ─────────────

    @Test fun `un corte a mitad de la subida no pierde ni duplica nada y el reintento es idempotente`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1) + workoutRecs(2) + routineRecs(1), s, pushBatch = 5)
        d.onboard()
        s.cutAfterApplying = 3                                                      // el servidor aplica 3 cambios y la red se corta SIN responder
        val first = d.run()
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.NETWORK, retryable = true), first)
        assertTrue(d.rows.values.all { it.dirty }, "el cliente no vio ninguna confirmación: todo sigue pendiente")
        assertEquals(3, state.rowCount(UID))
        val second = d.run() as SyncOutcome.Success
        assertEquals(d.rows.size, state.rowCount(UID)); assertTrue(d.rows.values.none { it.dirty })
        assertTrue(d.rows.values.all { it.rev == 1L })                              // las 3 ya aplicadas se reconocieron por contenido, sin subir de revisión
        assertEquals(d.rows.size, second.pushed)
    }

    @Test fun `un corte en la descarga no aplica nada a medias y el reintento converge`() = runBlocking {
        val dst = Device("B", emptyList(), server(), pageSize = 7); dst.onboard()
        val src = Device("A", (0..5).flatMap { workoutRecs(it) }, server()); src.onboard(); src.run()
        dst.server.failPullAfterPages = 2
        assertTrue(dst.run() is SyncOutcome.Failed)
        assertEquals(0, dst.rows.size, "H4: la descarga se aplica entera en una transacción; un corte no deja agregados partidos")
        assertEquals(0L, dst.store.metaState.cursor)
        assertTrue(dst.run() is SyncOutcome.Success)
        assertEquals(src.contentMap(), dst.contentMap())
    }

    @Test fun `paginacion con muchas filas y paginas pequenas`() = runBlocking {
        val src = Device("A", (0..24).flatMap { workoutRecs(it, exCount = 1, setCount = 2) }, server(), pushBatch = 10); src.onboard(); src.run()
        val dst = Device("B", emptyList(), server(), pageSize = 3); dst.onboard(); dst.run()
        assertEquals(src.contentMap(), dst.contentMap()); assertEquals(25, dst.live(Tables.WORKOUTS).size)
    }

    @Test fun `token caducado detiene la sincronizacion sin tocar datos y se recupera despues`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1), s); d.onboard()
        s.tokenExpired = true
        val before = d.rows.toMap()
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.AUTH, retryable = false), d.run())
        assertEquals(before, d.rows); assertEquals("AUTH", d.store.metaState.lastErrorKind)
        s.tokenExpired = false
        assertTrue(d.run() is SyncOutcome.Success); assertNull(d.store.metaState.lastErrorKind)
    }

    @Test fun `sin conexion no se pierde nada y los cambios pendientes se suben al reconectar`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1), s); d.onboard(); d.run()
        s.online = false
        d.insert(workoutRecs(2)); d.edit(Tables.WORKOUTS, uuid("w1"), "notes", JsonPrimitive("sin red"))
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.NETWORK, true), d.run())
        assertTrue(d.rows.values.any { it.dirty })
        s.online = true
        assertTrue(d.run() is SyncOutcome.Success); assertTrue(d.rows.values.none { it.dirty })
        assertEquals(2, state.user(UID).rows.values.count { it.deletedAt == null && it.rev >= 1 && it.data["id"] != null && it.data["workoutId"] == null && it.data["userId"] != null && it.data["durationSeconds"] != null })
    }

    @Test fun `una fila editada mientras subia se queda pendiente con la nueva revision base`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1), s); d.onboard(); d.run()
        d.edit(Tables.WORKOUTS, uuid("w1"), "notes", JsonPrimitive("v2"))
        val key = Key(Tables.WORKOUTS, uuid("w1"))
        // el servidor acepta la v2, pero mientras volaba la respuesta el usuario editó otra vez
        var edited = false
        val racing = object : RemoteApi by s {
            override suspend fun push(changes: List<PushChange>): PushResponse = s.push(changes).also {
                if (!edited) { edited = true; d.sync.transaction { put(records().first { r -> r.key == key }.edited("notes", JsonPrimitive("v3"), BASE_MS + 50_000_000)) } }
            }
        }
        // con un solo envío permitido se ve el estado intermedio: v3 NO se da por subida
        val onePush = object : RemoteApi by racing { var n = 0; override suspend fun push(changes: List<PushChange>): PushResponse { if (n++ > 0) throw RemoteException(RemoteException.Kind.NETWORK, "corte"); return racing.push(changes) } }
        assertTrue(SyncRunner(d.sync, onePush, { 1 }).run() is SyncOutcome.Failed)
        val mid = d.rows.getValue(key)
        assertEquals("v3", mid.str("notes")); assertEquals(2L, mid.rev); assertTrue(mid.dirty)      // v3 no se perdió ni se marcó como subida; su base ya es la revisión 2
        assertTrue(d.run() is SyncOutcome.Success)
        val srv = state.user(UID).rows.getValue(key)
        assertEquals("v3", (srv.data["notes"] as JsonPrimitive).content); assertEquals(3L, srv.rev)
        assertTrue(d.rows.getValue(key).let { it.rev == 3L && !it.dirty })
    }

    // ───────────── identidad y aislamiento ─────────────

    @Test fun `cada uid tiene su propio espacio y no hay acceso cruzado`() = runBlocking {
        val a = Device("A", workoutRecs(1), server("uid-A")); a.onboard(); a.run()
        val b = Device("B", workoutRecs(1, uid = "uid-B", name = "Otro usuario"), server("uid-B")); b.onboard(); b.run()
        assertEquals(a.rows.size, state.rowCount("uid-A")); assertEquals(b.rows.size, state.rowCount("uid-B"))
        val c = Device("C", emptyList(), server("uid-C")); c.onboard(); c.run()
        assertEquals(0, c.rows.size)                                                  // un tercer usuario no ve nada de los otros
        assertEquals("Otro usuario", state.user("uid-B").rows.values.first { it.data["durationSeconds"] != null }.data["name"].let { (it as JsonPrimitive).content })
    }

    @Test fun `servidor con datos antiguos y local divergente se resuelve sin perdida`() = runBlocking {
        val old = Device("viejo", workoutRecs(1) + routineRecs(1, name = "Vieja"), server()); old.onboard(); old.run()
        val local = Device("nuevo", workoutRecs(1).map { if (it.table == Tables.WORKOUTS) it.withStr("notes", "cambiada en local") else it } + workoutRecs(2) + routineRecs(1, name = "Vieja"), server())
        val plan = local.plan()
        assertEquals(1, plan.count(OAction.CONFLICT)); assertEquals(1, plan.count(OAction.ADOPT)); assertEquals(1, plan.count(OAction.UPLOAD))
        val before = local.rows.toMap()
        local.onboard(plan)
        before.forEach { (k, v) -> assertTrue(Content.same(v, local.rows.getValue(k))) }      // nada local se tocó
        assertEquals(1, local.store.conflictGroups.size)
        local.run()
        assertTrue(state.user(UID).rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).data["notes"].toString().contains("nota 1"), "el servidor conserva su versión hasta que el usuario decida")
    }

    @Test fun `importar y sincronizar no se pisan en paralelo`() = runBlocking {
        val events = mutableListOf<String>()
        val slow = object : RecStore {
            val inner = MemStore()
            override val boundIdentity: String get() = inner.boundIdentity
            override fun liveIdentity(): String? = inner.liveIdentity()
            override suspend fun <T> transaction(block: suspend RecTx.() -> T): T { events += "import-start"; delay(150); return inner.transaction(block).also { events += "import-end" } }
        }
        val s = server(); val d = Device("A", workoutRecs(0), s); d.onboard()
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), emptyList(), UID)
        val spy = object : RemoteApi by s { override suspend fun push(changes: List<PushChange>): PushResponse { events += "push"; return s.push(changes) } }
        val imp = async { ImportExecutor.apply(slow, plan) }
        delay(30)
        val syn = async { SyncRunner(d.sync, spy, { 1 }).run() }
        imp.await(); syn.await()
        assertTrue(events.indexOf("import-end") < events.indexOf("push"), "la subida no puede empezar durante la importación: $events")
    }

    @Test fun `espera exponencial con tope`() {
        assertEquals(30_000, Backoff.delayMs(0)); assertEquals(60_000, Backoff.delayMs(1)); assertEquals(240_000, Backoff.delayMs(3))
        assertEquals(6 * 3_600_000L, Backoff.delayMs(30)); assertTrue((0..40).map { Backoff.delayMs(it) }.zipWithNext().all { (x, y) -> y >= x })
        assertFailsWith<IllegalArgumentException> { Backoff.delayMs(-1) }
    }

    // ───────────── incorporación: atomicidad, estado obsoleto, servidor restaurado ─────────────

    @Test fun `la incorporacion se invalida si lo local cambia tras la vista previa`() = runBlocking {
        val d = Device("A", workoutRecs(1), server()); val plan = d.plan(); val proof = d.backup()
        d.insert(workoutRecs(2))
        val before = d.rows.toMap()
        assertFailsWith<OnboardingBlockedException> { Onboarding.apply(d.sync, plan, proof) }      // la copia ya no corresponde al estado
        assertEquals(before, d.rows); assertEquals(false, d.store.metaState.onboarded)
        d.onboard(d.plan())                                                                          // recalcular funciona
    }

    @Test fun `un fallo a mitad de la incorporacion no deja nada aplicado`() = runBlocking {
        val seed = Device("A", (0..3).flatMap { workoutRecs(it) }, server()); seed.onboard(); seed.run()
        val d = Device("B", workoutRecs(0) + routineRecs(9), server())
        val plan = d.plan(); val proof = d.backup(); val before = d.rows.toMap()
        for (failAt in listOf(1, 4, 20)) {
            d.store.failOnWrite = failAt
            assertFailsWith<IllegalStateException> { Onboarding.apply(d.sync, plan, proof) }
            assertEquals(before, d.rows, "escritura $failAt dejó filas a medias"); assertEquals(false, d.store.metaState.onboarded); assertTrue(d.store.conflictGroups.isEmpty())
        }
        d.store.failOnWrite = 0
        Onboarding.apply(d.sync, plan, proof)
        assertTrue(d.store.metaState.onboarded)
    }

    @Test fun `un tombstone solo local tambien se sube para que otros dispositivos no resuciten el dato`() = runBlocking {
        val dead = workoutRecs(1).map { if (it.table == Tables.WORKOUTS) it.softDeleted() else it }
        val d = Device("A", dead, server()); d.onboard(); d.run()
        assertTrue(state.user(UID).rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).deletedAt != null)
    }

    @Test fun `un servidor restaurado desde una copia antigua recupera lo que le falta sin pisar nada`() = runBlocking {
        val d = Device("A", workoutRecs(1), server()); d.onboard(); d.run()
        d.insert(workoutRecs(2) + workoutRecs(3)); d.run()
        assertEquals(27, state.rowCount(UID))
        // el servidor "vuelve atrás": solo conserva el primer entreno y un contador de versiones menor que el cursor del cliente
        val old = state.user(UID).rows.filterKeys { k -> k.id == uuid("w1") || RecIndex(d.rows.values).rootOf(d.rows.getValue(k))?.id == uuid("w1") }
        state.users[UID] = FakeServerState.User().also { u -> old.values.forEachIndexed { i, r -> r.version = (i + 1).toLong() }; u.rows.putAll(old); u.version = old.size.toLong() }
        val out = d.run() as SyncOutcome.Success
        assertEquals(27, state.rowCount(UID)); assertTrue(d.rows.values.none { it.dirty }); assertEquals(0, out.openConflicts)
        assertEquals(3, d.live(Tables.WORKOUTS).size)
    }

    @Test fun `importar datos que ya estan en el servidor con otro contenido produce conflicto y no pisa`() = runBlocking {
        val a = Device("A", workoutRecs(1), server()); a.onboard(); a.run()
        val b = Device("B", emptyList(), server())
        val modified = workoutRecs(1).map { if (it.table == Tables.WORKOUTS) it.withStr("notes", "versión de la copia") else it }
        ImportExecutor.apply(b.store, ImportPlanner.plan(ExportParser.parse(exportJson(modified).toByteArray(), UID), emptyList(), UID))
        val plan = b.plan()
        assertEquals(1, plan.count(OAction.CONFLICT))
        b.onboard(plan); b.run()
        assertEquals("nota 1", (state.user(UID).rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).data["notes"] as JsonPrimitive).content)   // el servidor no se tocó
        assertEquals("versión de la copia", b.rows.getValue(Key(Tables.WORKOUTS, uuid("w1"))).str("notes"))                                // ni lo importado
        assertEquals(1, b.store.conflictGroups.size)
    }

    // ayuda: nombre actual en el servidor de una rutina
    private fun Device.snapshotServerName(rid: String): String = (state.user(UID).rows.getValue(Key(Tables.ROUTINES, rid)).data["name"] as JsonPrimitive).content
}
