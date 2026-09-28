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

    private companion object { const val DB = "migration-test" }
}
