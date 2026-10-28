package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * El estado del buscador vive en el ViewModel: la misma instancia se entrega a la pantalla
 * cada vez que se compone, así que ir al detalle y volver no lo pierde.
 */
class ExerciseSearchStateTest {

    @Test fun `ir al detalle y volver conserva texto, filtros, seleccion y lista`() {
        val state = ExerciseSearchState()
        state.query = "remo"
        state.filters.add("Espalda Media")
        state.filtersExpanded = true
        state.selected.add("Seated Cable Rows")
        val list = state.listState

        // La pantalla de selección se va (detalle del ejercicio) y vuelve: no se llama a nada más
        assertEquals("remo", state.query)
        assertEquals(listOf("Espalda Media"), state.filters.toList())
        assertEquals(true, state.filtersExpanded)
        assertEquals(listOf("Seated Cable Rows"), state.selected.toList())
        assertSame(list, state.listState)
    }

    @Test fun `empezar una seleccion nueva solo cambia los marcados`() {
        val state = ExerciseSearchState()
        state.query = "press"
        state.filters.add("Pecho")
        state.selected.add("Old")
        state.beginSelection(listOf("Bench Press", "Dumbbell Flyes"))
        assertEquals(listOf("Bench Press", "Dumbbell Flyes"), state.selected.toList())
        assertEquals("press", state.query)            // la búsqueda solo la borra el usuario
        assertEquals(listOf("Pecho"), state.filters.toList())
    }
}
