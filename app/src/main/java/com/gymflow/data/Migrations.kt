package com.gymflow.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    /**
     * v1 → v2: preparación para sincronizar.
     *  • Columna `dirty` en todas las tablas. Lo que ya existía queda pendiente de
     *    subir, salvo el catálogo global (userId NULL), que nunca se sube.
     *  • Las tablas hijas se reconstruyen sin claves foráneas (SQLite no permite
     *    quitarlas con ALTER) y ganan `mainGroup`, rellenado desde el catálogo.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("exercises", "routines", "workouts", "body_measurements", "schedules", "achievements")) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
            }
            db.execSQL("UPDATE exercises SET dirty = 0 WHERE userId IS NULL")

            rebuild(
                db, "routine_exercises",
                "CREATE TABLE `routine_exercises_new` (`id` TEXT NOT NULL, `routineId` TEXT NOT NULL, `exerciseId` TEXT, `exerciseName` TEXT NOT NULL, `mainGroup` TEXT, `position` INTEGER NOT NULL, `notes` TEXT NOT NULL, `supersetGroup` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `dirty` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
                """
                INSERT INTO routine_exercises_new (id, routineId, exerciseId, exerciseName, mainGroup, position, notes, supersetGroup, createdAt, updatedAt, deletedAt, dirty)
                SELECT t.id, t.routineId, t.exerciseId, t.exerciseName,
                       COALESCE((SELECT e.mainGroup FROM exercises e WHERE e.id = t.exerciseId),
                                (SELECT e.mainGroup FROM exercises e WHERE e.name = t.exerciseName LIMIT 1)),
                       t.position, t.notes, t.supersetGroup, t.createdAt, t.updatedAt, t.deletedAt, 1
                  FROM routine_exercises t
                """,
                listOf("routineId", "exerciseId")
            )
            rebuild(
                db, "routine_sets",
                "CREATE TABLE `routine_sets_new` (`id` TEXT NOT NULL, `routineExerciseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `setType` TEXT NOT NULL, `targetWeightKg` REAL NOT NULL, `targetReps` INTEGER NOT NULL, `targetTimeSeconds` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `dirty` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
                """
                INSERT INTO routine_sets_new (id, routineExerciseId, position, setType, targetWeightKg, targetReps, targetTimeSeconds, createdAt, updatedAt, deletedAt, dirty)
                SELECT id, routineExerciseId, position, setType, targetWeightKg, targetReps, targetTimeSeconds, createdAt, updatedAt, deletedAt, 1
                  FROM routine_sets
                """,
                listOf("routineExerciseId")
            )
            rebuild(
                db, "workout_exercises",
                "CREATE TABLE `workout_exercises_new` (`id` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `exerciseId` TEXT, `exerciseName` TEXT NOT NULL, `mainGroup` TEXT, `position` INTEGER NOT NULL, `notes` TEXT NOT NULL, `supersetGroup` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `dirty` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
                """
                INSERT INTO workout_exercises_new (id, workoutId, exerciseId, exerciseName, mainGroup, position, notes, supersetGroup, createdAt, updatedAt, deletedAt, dirty)
                SELECT t.id, t.workoutId, t.exerciseId, t.exerciseName,
                       COALESCE((SELECT e.mainGroup FROM exercises e WHERE e.id = t.exerciseId),
                                (SELECT e.mainGroup FROM exercises e WHERE e.name = t.exerciseName LIMIT 1)),
                       t.position, t.notes, t.supersetGroup, t.createdAt, t.updatedAt, t.deletedAt, 1
                  FROM workout_exercises t
                """,
                listOf("workoutId", "exerciseId", "exerciseName")
            )
            rebuild(
                db, "workout_sets",
                "CREATE TABLE `workout_sets_new` (`id` TEXT NOT NULL, `workoutExerciseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `setType` TEXT NOT NULL, `weightKg` REAL NOT NULL, `reps` INTEGER NOT NULL, `timeSeconds` INTEGER NOT NULL, `rpe` REAL, `completed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `dirty` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
                """
                INSERT INTO workout_sets_new (id, workoutExerciseId, position, setType, weightKg, reps, timeSeconds, rpe, completed, createdAt, updatedAt, deletedAt, dirty)
                SELECT id, workoutExerciseId, position, setType, weightKg, reps, timeSeconds, rpe, completed, createdAt, updatedAt, deletedAt, 1
                  FROM workout_sets
                """,
                listOf("workoutExerciseId")
            )
        }

        private fun rebuild(db: SupportSQLiteDatabase, table: String, create: String, copy: String, indexed: List<String>) {
            db.execSQL(create)
            db.execSQL(copy.trimIndent())
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
            for (col in indexed) db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_$col` ON `$table` (`$col`)")
        }
    }

    /**
     * v2 → v3: estado de la sincronización v2. SOLO crea tablas nuevas: no toca ninguna fila existente ni ninguna tabla de datos
     * (los datos del usuario quedan exactamente igual). Lo ya sincronizado con el protocolo anterior vuelve a pasar por la
     * incorporación inicial, que compara contenidos y no pisa nada.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_revs` (`userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `tableName` TEXT NOT NULL, `id` TEXT NOT NULL, `rev` INTEGER NOT NULL, PRIMARY KEY(`userId`, `serverKey`, `tableName`, `id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `cursor` INTEGER NOT NULL, `onboarded` INTEGER NOT NULL, `lastSuccessAt` INTEGER, `lastErrorKind` TEXT, PRIMARY KEY(`userId`, `serverKey`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_conflicts` (`userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `rootTable` TEXT NOT NULL, `rootId` TEXT NOT NULL, `groupJson` TEXT NOT NULL, PRIMARY KEY(`userId`, `serverKey`, `rootTable`, `rootId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_archive` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `rootTable` TEXT NOT NULL, `rootId` TEXT NOT NULL, `resolution` TEXT NOT NULL, `at` INTEGER NOT NULL, `groupJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
