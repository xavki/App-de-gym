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
    private fun text(rel: String) = File(dir, rel).readText()

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
        val start = svc.indexOf("private fun importStore"); val end = svc.indexOf("\n\n", start)
        val body = svc.substring(start, end)
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
}
