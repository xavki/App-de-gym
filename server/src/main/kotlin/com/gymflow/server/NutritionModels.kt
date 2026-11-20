package com.gymflow.server

import kotlinx.serialization.Serializable

// ─── Nutrición ───────────────────────────────────────────────────────────────
// Las fechas de los registros son días naturales "YYYY-MM-DD" en la zona horaria
// del usuario (la decide el panel). Las cantidades van en kcal y gramos.

@Serializable
data class NutritionGoals(val kcal: Int, val proteinG: Double, val fatG: Double, val carbsG: Double)

/**
 * Objetivos vigentes en una fecha. [isDefault]: para esa fecha no hay objetivos guardados y se
 * usan los iniciales del plan. [effectiveFrom]: desde qué día valen (null si son los iniciales).
 */
@Serializable
data class GoalsResponse(val goals: NutritionGoals, val isDefault: Boolean, val updatedAt: Long? = null, val effectiveFrom: String? = null)

@Serializable
data class Macros(val kcal: Double, val proteinG: Double, val fatG: Double, val carbsG: Double) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, proteinG + o.proteinG, fatG + o.fatG, carbsG + o.carbsG)
    operator fun times(f: Double) = Macros(kcal * f, proteinG * f, fatG * f, carbsG * f)

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
    }
}

@Serializable
data class Meal(
    val code: String,
    val category: String, // BREAKFAST | LUNCH | SNACK | DINNER
    val name: String,
    val ingredients: String,
    val perServing: Macros,
    /** Días del menú semanal en que el plan propone esta comida (1 = lunes … 7 = domingo). */
    val planDays: List<Int>,
)

@Serializable
data class MealsResponse(val meals: List<Meal>)

@Serializable
data class NutritionLog(
    val id: String,
    val date: String,
    /** null en las comidas personalizadas. */
    val mealCode: String?,
    val mealName: String,
    val servings: Double,
    val perServing: Macros,
    val total: Macros,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LogsResponse(val date: String, val logs: List<NutritionLog>)

/** Comida que no está en el plan: nombre y valores por ración, introducidos a mano. */
@Serializable
data class CustomFood(val name: String, val kcal: Double, val proteinG: Double, val fatG: Double, val carbsG: Double)

/**
 * [id]: UUID generado por el cliente; repetir la petición con el mismo id no duplica.
 * Lleva [mealCode] (comida del plan) o [custom] (comida personalizada), nunca los dos.
 */
@Serializable
data class CreateLogRequest(
    val id: String,
    val mealCode: String? = null,
    val date: String? = null,
    val servings: Double = 1.0,
    val custom: CustomFood? = null,
)

@Serializable
data class UpdateLogRequest(val servings: Double)

@Serializable
data class DaySummary(val date: String, val consumed: Macros, val goals: NutritionGoals, val logs: Int)

@Serializable
data class DaysResponse(val from: String, val to: String, val days: List<DaySummary>)
