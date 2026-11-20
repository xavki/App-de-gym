package com.gymflow.server

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToLong

/** Resultado de registrar una comida. */
sealed interface CreateLogResult {
    data class Created(val log: NutritionLog) : CreateLogResult
    /** Reintento de una petición ya aplicada (mismo id): se devuelve el registro existente. */
    data class AlreadyExists(val log: NutritionLog) : CreateLogResult
    data object UnknownMeal : CreateLogResult
    /** El id ya existe pero con otro contenido, de otro usuario o borrado. */
    data object IdConflict : CreateLogResult
}

/** Qué se registra: una comida del catálogo del plan o una personalizada con valores introducidos a mano. */
sealed interface LogSource {
    data class Catalog(val code: String) : LogSource
    data class Custom(val name: String, val perServing: Macros) : LogSource
}

/** Todas las consultas filtran por user_id: un usuario nunca ve ni toca registros ajenos. */
class NutritionRepository(private val db: Database) {

    fun meals(): List<Meal> = db.tx { c ->
        c.prepareStatement(
            """
            SELECT code, category, name, ingredients, kcal, protein_g, fat_g, carbs_g, plan_days
              FROM nutrition_meals WHERE active ORDER BY sort_order
            """.trimIndent()
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Meal(
                            code = rs.getString(1),
                            category = rs.getString(2),
                            name = rs.getString(3),
                            ingredients = rs.getString(4),
                            perServing = Macros(rs.dbl(5), rs.dbl(6), rs.dbl(7), rs.dbl(8)),
                            planDays = (rs.getArray(9).array as Array<*>).map { (it as Number).toInt() },
                        )
                    )
                }
            }
        }
    }

    /** Objetivos vigentes el día [date]. */
    fun goals(uid: String, date: LocalDate): GoalsResponse = db.tx { c -> goalsOn(c, uid, date) }

    /**
     * Guarda los objetivos con efecto desde [today]: los días anteriores conservan los que
     * tenían. Guardar varias veces el mismo día sustituye la entrada de ese día.
     */
    fun saveGoals(uid: String, email: String?, goals: NutritionGoals, today: LocalDate): GoalsResponse = db.tx { c ->
        SyncRepository.ensureUser(c, uid, email)
        c.prepareStatement(
            """
            INSERT INTO nutrition_goal_history (user_id, effective_from, kcal, protein_g, fat_g, carbs_g) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, effective_from) DO UPDATE
               SET kcal = EXCLUDED.kcal, protein_g = EXCLUDED.protein_g, fat_g = EXCLUDED.fat_g,
                   carbs_g = EXCLUDED.carbs_g, updated_at = now()
            """.trimIndent()
        ).use { st ->
            st.setString(1, uid)
            st.setObject(2, today)
            st.setInt(3, goals.kcal)
            st.setBigDecimal(4, goals.proteinG.toBigDecimal())
            st.setBigDecimal(5, goals.fatG.toBigDecimal())
            st.setBigDecimal(6, goals.carbsG.toBigDecimal())
            st.executeUpdate()
        }
        // Copia de los objetivos actuales (tabla de V2), por compatibilidad
        c.prepareStatement(
            """
            INSERT INTO nutrition_goals (user_id, kcal, protein_g, fat_g, carbs_g) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE
               SET kcal = EXCLUDED.kcal, protein_g = EXCLUDED.protein_g, fat_g = EXCLUDED.fat_g,
                   carbs_g = EXCLUDED.carbs_g, updated_at = now()
            """.trimIndent()
        ).use { st ->
            st.setString(1, uid)
            st.setInt(2, goals.kcal)
            st.setBigDecimal(3, goals.proteinG.toBigDecimal())
            st.setBigDecimal(4, goals.fatG.toBigDecimal())
            st.setBigDecimal(5, goals.carbsG.toBigDecimal())
            st.executeUpdate()
        }
        goalsOn(c, uid, today)
    }

    fun logs(uid: String, date: LocalDate): List<NutritionLog> = db.tx { c ->
        c.prepareStatement("$SELECT_LOG WHERE user_id = ? AND log_date = ? AND deleted_at IS NULL ORDER BY created_at, id").use { st ->
            st.setString(1, uid)
            st.setObject(2, date)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toLog()) } }
        }
    }

    /**
     * Registra una comida copiando sus valores por ración (del catálogo, o los introducidos a
     * mano si es personalizada). Idempotente por [id]: si ya existe un registro igual de este
     * usuario, se devuelve sin crear otro.
     */
    fun create(uid: String, email: String?, id: UUID, source: LogSource, date: LocalDate, servings: Double): CreateLogResult = db.tx { c ->
        SyncRepository.ensureUser(c, uid, email)
        val mealCode = (source as? LogSource.Catalog)?.code
        val (name, per) = when (source) {
            is LogSource.Custom -> source.name to source.perServing
            is LogSource.Catalog -> c.prepareStatement("SELECT name, kcal, protein_g, fat_g, carbs_g FROM nutrition_meals WHERE code = ? AND active").use { st ->
                st.setString(1, source.code)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to Macros(rs.dbl(2), rs.dbl(3), rs.dbl(4), rs.dbl(5)) else null }
            } ?: return@tx CreateLogResult.UnknownMeal
        }
        val inserted = c.prepareStatement(
            """
            INSERT INTO nutrition_logs (id, user_id, log_date, meal_code, meal_name, servings,
                                        kcal_per_serving, protein_per_serving, fat_per_serving, carbs_per_serving)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent()
        ).use { st ->
            st.setObject(1, id)
            st.setString(2, uid)
            st.setObject(3, date)
            st.setString(4, mealCode)
            st.setString(5, name)
            st.setBigDecimal(6, servings.toBigDecimal())
            st.setBigDecimal(7, per.kcal.toBigDecimal())
            st.setBigDecimal(8, per.proteinG.toBigDecimal())
            st.setBigDecimal(9, per.fatG.toBigDecimal())
            st.setBigDecimal(10, per.carbsG.toBigDecimal())
            st.executeUpdate() == 1
        }
        val existing = c.prepareStatement("SELECT $LOG_COLUMNS, user_id, deleted_at IS NOT NULL FROM nutrition_logs WHERE id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs -> rs.next(); Triple(rs.toLog(), rs.getString(12), rs.getBoolean(13)) }
        }
        val (log, owner, deleted) = existing
        when {
            inserted -> CreateLogResult.Created(log)
            owner == uid && !deleted && log.date == date.toString() && log.mealCode == mealCode &&
                (mealCode != null || (log.mealName == name && log.perServing == per)) -> CreateLogResult.AlreadyExists(log)
            else -> CreateLogResult.IdConflict
        }
    }

    /** Cambia las raciones. null si el registro no existe, es de otro usuario o está borrado. */
    fun updateServings(uid: String, id: UUID, servings: Double): NutritionLog? = db.tx { c ->
        val updated = c.prepareStatement(
            "UPDATE nutrition_logs SET servings = ?, updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
        ).use { st ->
            st.setBigDecimal(1, servings.toBigDecimal())
            st.setObject(2, id)
            st.setString(3, uid)
            st.executeUpdate() == 1
        }
        if (!updated) return@tx null
        c.prepareStatement("$SELECT_LOG WHERE id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs -> rs.next(); rs.toLog() }
        }
    }

    /**
     * Borrado lógico. true si el registro es del usuario (también si ya estaba borrado:
     * repetir el borrado no es un error); false si no existe o es de otro usuario.
     */
    fun delete(uid: String, id: UUID): Boolean = db.tx { c ->
        c.prepareStatement(
            """
            UPDATE nutrition_logs SET deleted_at = COALESCE(deleted_at, now()), updated_at = now()
             WHERE id = ? AND user_id = ?
            """.trimIndent()
        ).use { st ->
            st.setObject(1, id)
            st.setString(2, uid)
            st.executeUpdate() == 1
        }
    }

    /**
     * Totales por día entre [from] y [to] (ambos incluidos), solo días con registros, cada uno con
     * los objetivos vigentes ese día. Más reciente primero.
     */
    fun days(uid: String, from: LocalDate, to: LocalDate): List<DaySummary> = db.tx { c ->
        c.prepareStatement(
            """
            SELECT d.log_date, d.kcal, d.protein, d.fat, d.carbs, d.n, g.kcal, g.protein_g, g.fat_g, g.carbs_g
              FROM (SELECT log_date, sum(servings * kcal_per_serving) AS kcal, sum(servings * protein_per_serving) AS protein,
                           sum(servings * fat_per_serving) AS fat, sum(servings * carbs_per_serving) AS carbs, count(*) AS n
                      FROM nutrition_logs
                     WHERE user_id = ? AND log_date BETWEEN ? AND ? AND deleted_at IS NULL
                     GROUP BY log_date) d
              LEFT JOIN LATERAL (
                    SELECT kcal, protein_g, fat_g, carbs_g FROM nutrition_goal_history h
                     WHERE h.user_id = ? AND h.effective_from <= d.log_date
                     ORDER BY h.effective_from DESC LIMIT 1) g ON true
             ORDER BY d.log_date DESC
            """.trimIndent()
        ).use { st ->
            st.setString(1, uid)
            st.setObject(2, from)
            st.setObject(3, to)
            st.setString(4, uid)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DaySummary(
                            date = rs.getObject(1, LocalDate::class.java).toString(),
                            consumed = Macros(rs.dbl(2), rs.dbl(3), rs.dbl(4), rs.dbl(5)).rounded(),
                            goals = if (rs.getObject(7) == null) DEFAULT_GOALS else NutritionGoals(rs.getInt(7), rs.dbl(8), rs.dbl(9), rs.dbl(10)),
                            logs = rs.getInt(6),
                        )
                    )
                }
            }
        }
    }

    fun summary(uid: String, date: LocalDate): DaySummary {
        val logs = logs(uid, date)
        val consumed = logs.fold(Macros.ZERO) { acc, l -> acc + l.total }.rounded()
        return DaySummary(date.toString(), consumed, goals(uid, date).goals, logs.size)
    }

    /** Fila del historial con el mayor effective_from <= [date]; sin ninguna, los objetivos iniciales. */
    private fun goalsOn(c: Connection, uid: String, date: LocalDate): GoalsResponse =
        c.prepareStatement(
            """
            SELECT kcal, protein_g, fat_g, carbs_g, updated_at, effective_from FROM nutrition_goal_history
             WHERE user_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1
            """.trimIndent()
        ).use { st ->
            st.setString(1, uid)
            st.setObject(2, date)
            st.executeQuery().use { rs ->
                if (rs.next()) GoalsResponse(
                    NutritionGoals(rs.getInt(1), rs.dbl(2), rs.dbl(3), rs.dbl(4)),
                    isDefault = false,
                    updatedAt = rs.getTimestamp(5).time,
                    effectiveFrom = rs.getObject(6, LocalDate::class.java).toString(),
                ) else GoalsResponse(DEFAULT_GOALS, isDefault = true)
            }
        }

    private fun ResultSet.toLog(): NutritionLog {
        val servings = dbl(5)
        val per = Macros(dbl(6), dbl(7), dbl(8), dbl(9))
        return NutritionLog(
            id = getObject(1, UUID::class.java).toString(),
            date = getObject(2, LocalDate::class.java).toString(),
            mealCode = getString(3),
            mealName = getString(4),
            servings = servings,
            perServing = per,
            total = (per * servings).rounded(),
            createdAt = getTimestamp(10).time,
            updatedAt = getTimestamp(11).time,
        )
    }

    companion object {
        /** Objetivos iniciales del plan de nutrición (se guardan tal cual, sin recalcular las kcal). */
        val DEFAULT_GOALS = NutritionGoals(kcal = 2450, proteinG = 185.0, fatG = 65.0, carbsG = 280.0)

        private const val LOG_COLUMNS = "id, log_date, meal_code, meal_name, servings, " +
            "kcal_per_serving, protein_per_serving, fat_per_serving, carbs_per_serving, created_at, updated_at"
        private const val SELECT_LOG = "SELECT $LOG_COLUMNS FROM nutrition_logs"

        private fun ResultSet.dbl(i: Int): Double = (getBigDecimal(i) ?: BigDecimal.ZERO).toDouble()

        private fun Double.round1() = (this * 10).roundToLong() / 10.0
        fun Macros.rounded() = Macros(kcal.round1(), proteinG.round1(), fatG.round1(), carbsG.round1())
    }
}
