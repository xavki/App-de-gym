package com.gymflow.synccore

/**
 * SQL de las migraciones de las tablas de sincronización de Room, en Kotlin PURO para poder ejecutarlo en SQLite real en la JVM (pruebas).
 * `Migrations.kt` (Android) usa exactamente estas sentencias.
 */
object RoomMigrationSql {
    /** `sync_revs` tal como la creó la versión 3 distribuida (clave sin usuario). NO se modifica: una versión de esquema ya entregada no cambia de significado. */
    const val SYNC_REVS_V3 =
        "CREATE TABLE IF NOT EXISTS `sync_revs` (`serverKey` TEXT NOT NULL, `tableName` TEXT NOT NULL, `id` TEXT NOT NULL, `rev` INTEGER NOT NULL, PRIMARY KEY(`serverKey`, `tableName`, `id`))"

    /** `sync_revs` de la versión 4: la revisión pertenece a (usuario, servidor, tabla, id). */
    const val SYNC_REVS_V4 =
        "CREATE TABLE IF NOT EXISTS `sync_revs` (`userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `tableName` TEXT NOT NULL, `id` TEXT NOT NULL, `rev` INTEGER NOT NULL, PRIMARY KEY(`userId`, `serverKey`, `tableName`, `id`))"

    /** Propietario de cada fila de negocio (los hijos, por su cadena de padres). */
    private const val OWNERS = """
        SELECT 'exercises' AS t, id, userId AS u FROM exercises
        UNION ALL SELECT 'routines', id, userId FROM routines
        UNION ALL SELECT 'workouts', id, userId FROM workouts
        UNION ALL SELECT 'body_measurements', id, userId FROM body_measurements
        UNION ALL SELECT 'schedules', id, userId FROM schedules
        UNION ALL SELECT 'achievements', id, userId FROM achievements
        UNION ALL SELECT 'routine_exercises', c.id, p.userId FROM routine_exercises c JOIN routines p ON p.id = c.routineId
        UNION ALL SELECT 'routine_sets', c.id, p.userId FROM routine_sets c JOIN routine_exercises e ON e.id = c.routineExerciseId JOIN routines p ON p.id = e.routineId
        UNION ALL SELECT 'workout_exercises', c.id, p.userId FROM workout_exercises c JOIN workouts p ON p.id = c.workoutId
        UNION ALL SELECT 'workout_sets', c.id, p.userId FROM workout_sets c JOIN workout_exercises e ON e.id = c.workoutExerciseId JOIN workouts p ON p.id = e.workoutId
    """

    /**
     * v3 → v4. Reconstruye SOLO `sync_revs`: cada revisión conserva su valor y pasa a su usuario propietario (deducido de la fila de datos).
     * Una revisión sin fila de datos o sin propietario se descarta: es solo una caché de la última revisión del servidor, así que esa fila
     * volverá a reconocerse (contenido idéntico) en la siguiente sincronización. Ninguna tabla de datos se toca. Nada destructivo sobre datos.
     */
    val MIGRATE_3_4: List<String> = listOf(
        "CREATE TABLE `sync_revs_new` (`userId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `tableName` TEXT NOT NULL, `id` TEXT NOT NULL, `rev` INTEGER NOT NULL, PRIMARY KEY(`userId`, `serverKey`, `tableName`, `id`))",
        "INSERT INTO `sync_revs_new` (`userId`, `serverKey`, `tableName`, `id`, `rev`) " +
            "SELECT o.u, r.serverKey, r.tableName, r.id, r.rev FROM `sync_revs` r JOIN ($OWNERS) o ON o.t = r.tableName AND o.id = r.id WHERE o.u IS NOT NULL",
        "DROP TABLE `sync_revs`",
        "ALTER TABLE `sync_revs_new` RENAME TO `sync_revs`",
    )
}
