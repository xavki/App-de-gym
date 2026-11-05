package com.gymflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardas estáticas de la ronda 3 (N-01, N-02, N-05, M-03). Leen el código de producción; no sustituyen a las pruebas instrumentadas. */
class Round3GuardTest {
    private val dir = File("src/main/java/com/gymflow")
    private fun all() = dir.walkTopDown().filter { it.extension == "kt" }.toList()
    /** Lee con finales de línea normalizados (LF): el mismo fuente en un checkout CRLF de Windows debe dar el mismo resultado (R3-01). */
    private fun text(rel: String) = File(dir, rel).readText().replace("\r\n", "\n")

    /** Texto desde [start] hasta la primera línea en blanco, con LF o CRLF. Falla (no devuelve -1 silencioso) si no hay línea en blanco. */
    private fun untilBlankLine(src: String, start: Int): String {
        require(start >= 0) { "inicio no encontrado" }
        val m = Regex("""\r?\n[ \t]*\r?\n""").find(src, start) ?: return src.substring(start)
        return src.substring(start, m.range.first)
    }

    @Test fun `N-01 todo uso de await de Task tiene una extension visible`() {
        for (f in all()) {
            val t = f.readText()
            if (!t.contains(").await()") && !t.contains("}.await()")) continue
            val visible = t.contains("import com.gymflow.data.await") || t.contains("package com.gymflow.data")
            assertTrue("${f.name} usa await() sin extensión visible", visible)
        }
        assertTrue(text("data/TaskAwait.kt").contains("internal suspend fun <T> Task<T>.await()"))
    }

    @Test fun `N-02 la importacion local no depende del servidor`() {
        val svc = text("sync/SyncService.kt")
        val body = untilBlankLine(svc, svc.indexOf("private fun importStore"))
        assertFalse("importStore no debe usar la URL del servidor", body.contains("serverKey(") || body.contains("liveIdentity("))
        assertTrue(body.contains("liveLocalIdentity"))
        // y la cuenta se captura una sola vez antes de suspender
        val plan = svc.substring(svc.indexOf("suspend fun planImport"), svc.indexOf("suspend fun applyImport"))
        assertEquals(1, Regex("""\buid\(\)""").findAll(plan).count())
    }

    @Test fun `N-05 la version de Room es 4 y existe la migracion 3 a 4`() {
        assertTrue(text("data/GymDatabase.kt").contains("version = 4"))
        val m = text("data/Migrations.kt")
        assertTrue(m.contains("Migration(3, 4)"))
        assertTrue(m.contains("arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"))
        // la v3 distribuida no se altera: MIGRATION_2_3 crea sync_revs con la clave antigua
        assertTrue(m.contains("RoomMigrationSql.SYNC_REVS_V3"))
        assertTrue(text("data/SyncEntities.kt").contains("primaryKeys = [\"userId\", \"serverKey\", \"tableName\", \"id\"]"))
        assertFalse("no se permite migración destructiva", all().any { it.readText().contains("fallbackToDestructiveMigration") })
    }

    @Test fun `M-03 las rutas que cambian Room avisan a las pantallas`() {
        val svc = text("sync/SyncService.kt")
        assertTrue(Regex("DataEvents\\.notifyChanged\\(\\)").findAll(svc).count() >= 3)   // importar, incorporar, resolver
        assertTrue(text("GymFlowViewModel.kt").contains("DataEvents.changed.collect"))
    }

    @Test fun `N-08 el listener de sesion del ViewModel se retira`() {
        val vm = text("ImportSyncViewModel.kt")
        assertTrue(vm.contains("addAuthStateListener(authListener)"))
        assertTrue(vm.contains("removeAuthStateListener(authListener)"))
    }

    // ─── R3-01: la extracción de bloques funciona con LF y con CRLF ───
    private val sample = "private fun importStore(a: A) =\n    X(a) { liveLocalIdentity() }\n\nprivate fun otra() = serverKey()\n"

    @Test fun `R3-01 el bloque se extrae igual con LF y con CRLF`() {
        val lf = sample; val crlf = sample.replace("\n", "\r\n")
        val a = untilBlankLine(lf, lf.indexOf("private fun importStore"))
        val b = untilBlankLine(crlf, crlf.indexOf("private fun importStore"))
        assertEquals(a.replace("\r", ""), b.replace("\r", ""))
        assertTrue(a.contains("liveLocalIdentity") && !a.contains("serverKey"))
        assertTrue(b.contains("liveLocalIdentity") && !b.contains("serverKey"))
    }

    @Test fun `R3-01 la guarda sigue detectando una dependencia del servidor con ambos finales de linea`() {
        for (nl in listOf("\n", "\r\n")) {
            val malo = "private fun importStore(a: A) =${nl}    X(a) { liveIdentity(app) }${nl}${nl}fun z() {}${nl}"
            val body = untilBlankLine(malo, malo.indexOf("private fun importStore"))
            assertTrue(body.contains("liveIdentity("))      // el defecto N-02 se detectaría
            assertFalse(body.contains("fun z"))
        }
    }

    @Test fun `R3-01 el lector de fuentes entrega siempre LF`() {
        assertFalse(text("sync/SyncService.kt").contains("\r"))
    }

    @Test fun `importacion heredada incremental usa la politica y el propietario real`() {
        val imp = text("data/FirestoreImporter.kt")
        assertTrue(imp.contains("runLegacyImport(") && imp.contains("legacyOwner("))
        assertFalse("no debe volver a filtrar por has* global", imp.contains(".filterNot { repo.has"))
        assertTrue(text("data/GymRepository.kt").contains("suspend fun legacyOwner"))
    }

    // ─── Ronda 5 ───
    @Test fun `R4-02 la importacion heredada revalida dentro de la transaccion y propaga la cancelacion`() {
        val imp = text("data/FirestoreImporter.kt")
        assertTrue(imp.contains("atomically = { block -> repo.inTransaction(block) }"))
        assertTrue(imp.contains("onComplete ="))
        // la marca de «hecho» solo vive dentro de onComplete (que runLegacyImport ejecuta tras revalidar la cuenta)
        val idx = imp.indexOf("putBoolean(key, true)"); assertTrue(idx > imp.indexOf("onComplete ="))
        assertEquals(1, Regex("putBoolean\\(key, true\\)").findAll(imp).count())
        // todo catch (e: Exception) del importador va precedido de la relectura de CancellationException
        val catches = Regex("catch \\(e: (kotlinx\\.coroutines\\.)?(CancellationException|Exception)\\)").findAll(imp).map { it.groupValues[2] }.toList()
        assertTrue(catches.zipWithNext().all { (a, b) -> b != "Exception" || a == "CancellationException" } && catches.first() == "CancellationException")
    }

    @Test fun `R4-03 las pantallas no usan el tamano de la lista como clave de cache`() {
        for (f in listOf("ProgressScreens.kt", "ExerciseProgressScreen.kt", "PRBoardScreen.kt", "MuscleMapScreen.kt")) {
            val t = text(f)
            assertFalse("$f cachea con size del historial", Regex("""remember\([^)]*(history|workoutHistory)\.size""").containsMatchIn(t))
            assertFalse("$f cachea con size de personalizados", Regex("""remember\([^)]*customExercises\.size""").containsMatchIn(t))
        }
        assertTrue(text("ProgressScreens.kt").contains("HistoryFingerprint.of(history)"))
    }

    // ─── Ronda 6 ───
    private fun androidTest(rel: String) = File("src/androidTest/java/com/gymflow", rel).readText().replace("\r\n", "\n")

    @Test fun `R5-01 el test Compose no importa assertDoesNotExist y conserva sus tres tests originales`() {
        val t = androidTest("HomeProgressReloadTest.kt")
        assertFalse("assertDoesNotExist es un miembro de SemanticsNodeInteraction, no una extension importable", t.contains("import androidx.compose.ui.test.assertDoesNotExist"))
        for (name in listOf("homeShowsReplacedWorkoutWithSameCountAndSameId", "homeReflectsNameChangeAndDateChangeWithSameCount", "progressRecordsReflectWeightChangeWithSameCount"))
            assertTrue("falta el test $name", t.contains("fun $name()"))
        assertTrue(t.contains("muscleMapFollowsCustomExerciseGroupChangeWithSameCardinality") && t.contains("muscleMapFollowsGlobalCatalogGroupChangeWithSameCardinality"))
        // sin coincidencias ambiguas por subcadena en las aserciones de contenido
        assertFalse(Regex("""onNodeWithText\([^)]*substring\s*=\s*true""").containsMatchIn(t))
    }

    @Test fun `R5-02 la importacion heredada usa generacion de sesion y revalida tras write`() {
        val vm = text("GymFlowViewModel.kt")
        assertTrue(vm.contains("sessions.bump()") && vm.contains("sessions.capture(uid)") && vm.contains("sessions.isCurrent(token"))
        assertTrue(vm.contains("auth.removeAuthStateListener(authListener)"))
        val core = File("../syncCore/src/main/kotlin/com/gymflow/synccore/LegacyImport.kt").readText().replace("\r\n", "\n")
        val tx = core.substringAfter("atomically {").substringBefore("written++")
        assertTrue("write debe ir seguido de otra comprobacion dentro de la transaccion", tx.indexOf("write(item)") in 0 until tx.lastIndexOf("stillActive()"))
    }

    @Test fun `R5-03 el mapa muscular y el catalogo se invalidan por contenido`() {
        assertFalse(text("MuscleMapScreen.kt").contains("catalog.size"))
        val ps = text("ProgressScreens.kt")
        assertFalse("el catalogo no se cachea por tamano", ps.contains("getCached().size"))
        assertTrue(ps.contains("ExerciseRepository.catalogRevision"))
        assertTrue(text("ExerciseRepository.kt").contains("var catalogRevision by mutableIntStateOf"))
    }
}
