package com.gymflow

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Estado del buscador de ejercicios. Lo guarda el ViewModel, no la pantalla: al abrir el detalle
 * de un ejercicio la pantalla sale de la composición y antes se perdían texto, filtros, selección
 * y posición. Solo se limpia cuando el usuario lo hace (botón × o "Limpiar").
 */
class ExerciseSearchState {
    var query by mutableStateOf("")
    val filters = mutableStateListOf<String>()
    var filtersExpanded by mutableStateOf(false)
    /** Ejercicios marcados en esta selección (para la rutina que se está creando o editando). */
    val selected = mutableStateListOf<String>()
    /** Posición de la lista: se recupera al volver del detalle. */
    val listState = LazyListState()

    /** Al entrar desde una rutina: la selección parte de lo que ya tiene; búsqueda y filtros se conservan. */
    fun beginSelection(alreadySelected: List<String>) {
        selected.clear()
        selected.addAll(alreadySelected)
    }
}

/** Categoría del filtro (en español) → palabras que la identifican en el grupo o los músculos del ejercicio. */
private val FILTER_ALIASES = mapOf(
    "fuerza" to listOf("strength"), "pecho" to listOf("chest"), "bíceps" to listOf("biceps"),
    "tríceps" to listOf("triceps"), "hombros" to listOf("shoulders"), "gemelos" to listOf("calves"),
    "glúteos" to listOf("glutes"), "dorsales" to listOf("lats"), "cuádriceps" to listOf("quads"),
    "isquiotibiales" to listOf("hamstrings"), "abdominales" to listOf("abs"), "abductores" to listOf("abductor"),
    "aductores" to listOf("adductor"), "antebrazo" to listOf("forearm"), "cuello" to listOf("neck"),
    "trapecios" to listOf("trap"), "espalda baja" to listOf("lower back"), "espalda media" to listOf("mid back", "middle back"),
)

/** Mismas reglas de filtro que antes (grupo principal + músculos, nombres en español o inglés). */
fun ExerciseDefinition.matchesFilters(filters: List<String>): Boolean {
    if (filters.isEmpty()) return true
    val cats = (mainGroup.split(",") + musclesUsed.split(",") + listOfNotNull(subCategory)).map { it.trim().lowercase() }
    return filters.any { filter ->
        val f = filter.lowercase()
        cats.any { it.contains(f) } || FILTER_ALIASES[f].orEmpty().any { alias -> cats.any { it.contains(alias) } }
    }
}
