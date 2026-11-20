package com.gymflow.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

    @Test fun migrate1To2KeepsDataAndPreparesSync() {
        helper.createDatabase(DB, 1).apply {
            // Catálogo global + un personalizado
            execSQL("INSERT INTO exercises VALUES ('g1', NULL, 'Bench Press', 'Press banca', 'Pecho', '', NULL, '', NULL, NULL, NULL, NULL, NULL, '', 0, 1, 1, NULL)")
            execSQL("INSERT INTO exercises VALUES ('c1', 'u', 'Mi curl', NULL, 'Bíceps', '', NULL, '', NULL, NULL, NULL, NULL, NULL, '', 1, 1, 1, NULL)")
            execSQL("INSERT INTO routines VALUES ('r1', 'u', 'Push', 1, 1, NULL)")
            execSQL("INSERT INTO routine_exercises VALUES ('re1', 'r1', 'g1', 'Bench Press', 0, '', NULL, 1, 1, NULL)")
            execSQL("INSERT INTO routine_sets VALUES ('rs1', 're1', 0, 'NORMAL', 60.0, 10, 0, 1, 1, NULL)")
            execSQL("INSERT INTO workouts VALUES ('w1', 'u', 'r1', 'Push', 5, 3600, 80.0, '', 5, 5, NULL)")
            execSQL("INSERT INTO workout_exercises VALUES ('we1', 'w1', NULL, 'Mi curl', 0, '', NULL, 5, 5, NULL)")
            execSQL("INSERT INTO workout_exercises VALUES ('we2', 'w1', NULL, 'Desconocido', 1, '', NULL, 5, 5, NULL)")
            execSQL("INSERT INTO workout_sets VALUES ('ws1', 'we1', 0, 'NORMAL', 20.0, 12, 0, 8.5, 1, 5, 5, NULL)")
            close()
        }

        // Room valida que el esquema resultante es exactamente el de la versión 2
        val db = helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2)

        fun one(sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
        assertEquals("0", one("SELECT dirty FROM exercises WHERE id = 'g1'"))  // el catálogo global no se sube
        assertEquals("1", one("SELECT dirty FROM exercises WHERE id = 'c1'"))
        assertEquals("1", one("SELECT dirty FROM workout_sets WHERE id = 'ws1'"))
        assertEquals("Pecho", one("SELECT mainGroup FROM routine_exercises WHERE id = 're1'"))   // por exerciseId
        assertEquals("Bíceps", one("SELECT mainGroup FROM workout_exercises WHERE id = 'we1'"))  // por nombre
        assertNull(one("SELECT mainGroup FROM workout_exercises WHERE id = 'we2'"))
        assertEquals("8.5", one("SELECT rpe FROM workout_sets WHERE id = 'ws1'"))
        assertEquals("0", one("SELECT count(*) FROM pragma_foreign_key_list('workout_sets')"))
    }

    @Test fun migrate2To3OnlyAddsSyncTablesAndKeepsEveryRow() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO routines VALUES ('r1', 'u', 'Push', 1, 1, NULL, 1)")
            execSQL("INSERT INTO workouts VALUES ('w1', 'u', 'r1', 'Push', 5, 3600, 80.0, '', 5, 5, NULL, 0)")
            close()
        }
        // Requiere app/schemas/.../3.json = esquema de la v3 DISTRIBUIDA (commit 1a17b49). PENDIENTE: generarlo compilando ese commit y conservarlo.
        val db = helper.runMigrationsAndValidate(DB, 3, true, Migrations.MIGRATION_2_3)
        fun one(sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
        assertEquals("Push", one("SELECT name FROM routines WHERE id = 'r1'"))          // los datos del usuario, intactos
        assertEquals("0", one("SELECT dirty FROM workouts WHERE id = 'w1'"))             // y su estado de sincronización, también
        for (t in listOf("sync_revs", "sync_state", "sync_conflicts", "sync_archive")) assertEquals("0", one("SELECT count(*) FROM $t"))
    }

    @Test fun migrate3To4KeepsDataAndAssignsRevisionsToTheirOwners() {
        // Requiere 3.json (v3 distribuida, commit 1a17b49) y 4.json (se genera al compilar esta rama). PENDIENTE de ejecución en emulador desechable.
        helper.createDatabase(DB, 3).apply {
            execSQL("INSERT INTO routines VALUES ('r-a', 'uid-A', 'Push', 1, 1, NULL, 0)")
            execSQL("INSERT INTO routines VALUES ('r-b', 'uid-B', 'Pull', 1, 1, NULL, 0)")
            execSQL("INSERT INTO routine_exercises VALUES ('re-a', 'r-a', NULL, 'Bench', NULL, 0, '', NULL, 1, 1, NULL, 0)")
            execSQL("INSERT INTO sync_revs VALUES ('srv', 'routines', 'r-a', 5)")
            execSQL("INSERT INTO sync_revs VALUES ('srv', 'routine_exercises', 're-a', 6)")
            execSQL("INSERT INTO sync_revs VALUES ('srv', 'routines', 'r-b', 7)")
            execSQL("INSERT INTO sync_revs VALUES ('srv', 'routines', 'sin-fila', 9)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 4, true, Migrations.MIGRATION_3_4)
        fun one(sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
        assertEquals("2", one("SELECT count(*) FROM routines"))                                   // datos intactos
        assertEquals("1", one("SELECT count(*) FROM routine_exercises"))
        assertEquals("5", one("SELECT rev FROM sync_revs WHERE userId='uid-A' AND tableName='routines' AND id='r-a'"))
        assertEquals("6", one("SELECT rev FROM sync_revs WHERE userId='uid-A' AND tableName='routine_exercises'"))
        assertEquals("7", one("SELECT rev FROM sync_revs WHERE userId='uid-B' AND id='r-b'"))
        assertEquals("3", one("SELECT count(*) FROM sync_revs"))                                  // la huérfana se descarta
    }

    @Test fun migrateAllFrom1To4() {
        helper.createDatabase(DB, 1).apply { execSQL("INSERT INTO routines VALUES ('r1', 'u', 'Push', 1, 1, NULL)"); close() }
        val db = helper.runMigrationsAndValidate(DB, 4, true, *Migrations.ALL)
        db.query("SELECT name FROM routines WHERE id='r1'").use { assertEquals("Push", it.apply { moveToFirst() }.getString(0)) }
    }

    private companion object { const val DB = "migration-test" }
}
