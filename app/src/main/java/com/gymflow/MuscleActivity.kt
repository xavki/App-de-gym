package com.gymflow

import java.text.Normalizer
import java.util.Date

// ════════════════════════════════════════════════════════════════════════════
// Actividad por grupo muscular para el mapa de Progreso.
//
// Se conserva del cálculo anterior: últimos 7 días e intensidad = series del grupo /
// series del grupo con más. Los 7 días son los mismos que "Semana" en Progreso
// (hoy y los 6 anteriores, TrainingStats.inPeriod); antes eran "ahora − 168 h" y un
// entreno del octavo día natural salía en el mapa pero no en las estadísticas.
// Cambia (documentado en RENOVACION.md):
// - Qué serie cuenta: la misma regla que el resto de Progreso (TrainingStats.isWorkSet,
//   sin calentamientos ni series vacías). Antes contaba isCompleted || weight > 0, así
//   que el mapa y la tarjeta "Series por grupo muscular" daban cifras distintas.
// - Cómo se asigna el grupo a cada ejercicio:
// antes se buscaban palabras en el nombre y un mismo ejercicio sumaba en varios
// grupos por error ("Leg Press" → pecho, "Lateral Raise" → gemelos, todo "curl"
// → isquios, y los nombres en español no casaban). Ahora cada ejercicio suma en
// UN grupo: su músculo principal del catálogo (primero de musclesUsed), o su
// mainGroup, o, si no está en el catálogo, la palabra clave más larga que case.
// El cardio no es un músculo: se cuenta aparte y no entra en la escala.
// ════════════════════════════════════════════════════════════════════════════

data class MuscleActivity(
    /** Series por grupo (ids de [GROUPS]) en el periodo. */
    val sets: Map<String, Int>,
    /** 0..1, relativo al grupo con más series. */
    val intensity: Map<String, Float>,
    val cardioSets: Int,
    /** Series de ejercicios sin grupo identificable (cuerpo completo, estiramientos genéricos…). */
    val unassignedSets: Int,
) {
    companion object {
        const val CARDIO = "cardio"

        /** Grupos dibujados en el mapa, con su nombre en español, en orden de lectura. */
        val GROUPS = listOf(
            "chest" to "Pecho", "back" to "Espalda", "shoulders" to "Hombros",
            "biceps" to "Bíceps", "triceps" to "Tríceps", "forearms" to "Antebrazos",
            "abs" to "Abdominales", "glutes" to "Glúteos", "quads" to "Cuádriceps",
            "hamstrings" to "Isquiotibiales", "calves" to "Gemelos",
        )

        // Nombres de músculo del catálogo (musclesUsed / mainGroup), sin tildes y en minúsculas
        private val MUSCLE_ALIASES = mapOf(
            "pecho" to "chest", "pecho superior" to "chest", "pecho inferior" to "chest", "chest" to "chest",
            "espalda" to "back", "espalda media" to "back", "espalda baja" to "back", "dorsal" to "back",
            "dorsales" to "back", "trapecio" to "back", "trapecios" to "back", "lats" to "back",
            "middle back" to "back", "lower back" to "back", "traps" to "back",
            "hombros" to "shoulders", "hombro" to "shoulders", "hombros completos" to "shoulders", "shoulders" to "shoulders",
            "biceps" to "biceps", "triceps" to "triceps",
            "antebrazo" to "forearms", "antebrazos" to "forearms", "agarre" to "forearms", "forearms" to "forearms",
            "abdominales" to "abs", "abdominales inferiores" to "abs", "oblicuos" to "abs", "core" to "abs", "abdominals" to "abs",
            "gluteos" to "glutes", "gluteo" to "glutes", "gluteo medio" to "glutes", "glutes" to "glutes",
            "cuadriceps" to "quads", "quadriceps" to "quads",
            "isquiotibiales" to "hamstrings", "isquios" to "hamstrings", "hamstrings" to "hamstrings",
            "gemelos" to "calves", "calves" to "calves",
            "cardio" to CARDIO,
        )

        // Palabras clave del cálculo anterior, solo para ejercicios que no están en el catálogo
        private val KEYWORDS = listOf(
            "chest" to listOf("press", "chest", "pecho", "fly", "dip"),
            "back" to listOf("row", "pull", "lat", "deadlift", "back", "espalda", "chin"),
            "shoulders" to listOf("shoulder", "overhead", "lateral raise", "hombro", "delts", "press arnold"),
            "biceps" to listOf("curl", "bicep", "bícep"),
            "triceps" to listOf("tricep", "trícep", "pushdown", "extension", "dip"),
            "abs" to listOf("crunch", "sit-up", "abs", "plank", "core", "abdominales"),
            "quads" to listOf("squat", "leg press", "lunge", "quad", "sentadilla"),
            "hamstrings" to listOf("deadlift", "hamstring", "curl", "leg curl", "isquio"),
            "glutes" to listOf("glute", "hip thrust", "bridge", "glúteo"),
            "calves" to listOf("calf", "raise", "gemelo"),
            "forearms" to listOf("forearm", "wrist", "antebrazo"),
            CARDIO to listOf("run", "cardio", "treadmill", "bike", "rowing", "burpee", "jump"),
        )

        private fun norm(s: String) =
            Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        private fun alias(s: String): String? {
            val n = norm(s)
            return MUSCLE_ALIASES[n] ?: if (n.startsWith("deltoides")) "shoulders" else null
        }

        /** Grupo de un ejercicio del catálogo: cardio por mainGroup; si no, primer músculo; si no, mainGroup. */
        fun groupFromCatalog(mainGroup: String, musclesUsed: String): String? {
            val main = alias(mainGroup)
            if (main == CARDIO) return CARDIO
            return musclesUsed.split(',').firstOrNull()?.let(::alias) ?: main
        }

        /** Grupo por palabras del nombre (cálculo anterior); gana la palabra más larga que case. */
        fun groupFromName(exerciseName: String): String? {
            val name = exerciseName.lowercase()
            return KEYWORDS.flatMap { (g, kws) -> kws.filter { name.contains(it) }.map { it to g } }
                .maxByOrNull { it.first.length }?.second
        }

        fun compute(
            history: List<WorkoutSession>,
            catalog: Map<String, Pair<String, String>>,   // nombre → (mainGroup, musclesUsed)
            now: Date = Date(),
        ): MuscleActivity {
            val count = mutableMapOf<String, Int>()
            var unassigned = 0
            TrainingStats.inPeriod(history, TrainingStats.Period.WEEK, now.time).forEach { session ->
                session.exercises.forEach { ex ->
                    val n = ex.sets.count(TrainingStats::isWorkSet)
                    if (n == 0) return@forEach
                    val group = catalog[ex.exerciseName]?.let { (m, u) -> groupFromCatalog(m, u) }
                        ?: groupFromName(ex.exerciseName)
                    if (group == null) unassigned += n else count[group] = (count[group] ?: 0) + n
                }
            }
            val cardio = count.remove(CARDIO) ?: 0
            val max = (count.values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            return MuscleActivity(
                sets = count,
                intensity = count.mapValues { (_, v) -> (v / max).coerceIn(0f, 1f) },
                cardioSets = cardio,
                unassignedSets = unassigned,
            )
        }
    }
}
