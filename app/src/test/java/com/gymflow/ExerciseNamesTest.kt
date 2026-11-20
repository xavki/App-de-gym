package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Nombres en español, búsqueda y filtros del catálogo (sin Android: se lee el JSON del proyecto). */
class ExerciseNamesTest {

    companion object {
        private lateinit var table: Map<String, String>

        @BeforeClass @JvmStatic fun load() {
            val json = File("src/main/assets/exercise_names_es.json").readText(Charsets.UTF_8)
            table = ExerciseNamesEs.parse(json)
            ExerciseNamesEs.loadFrom(json)
        }
    }

    private fun def(name: String, group: String = "", muscles: String = "") =
        ExerciseDefinition(name = name, mainGroup = group, musclesUsed = muscles)

    @Test fun `ejemplos de terminologia de gimnasio`() {
        assertEquals("Press de banca", ExerciseNamesEs.display("Bench Press"))
        assertEquals("Jalón al pecho", ExerciseNamesEs.display("Lat Pulldown"))
        assertEquals("Peso muerto rumano", ExerciseNamesEs.display("Romanian Deadlift"))
        assertEquals("Elevaciones laterales", ExerciseNamesEs.display("Lateral Raise"))
        assertEquals("Remo sentado en polea", ExerciseNamesEs.display("Seated Cable Row"))
        assertEquals("Sentadilla con barra", ExerciseNamesEs.display("Barbell Squat"))
    }

    @Test fun `la tabla cubre el catalogo y no tiene huecos`() {
        assertTrue("se esperaban al menos los 873 del catálogo", table.size >= 873)
        table.forEach { (en, es) ->
            assertTrue("vacía: $en", es.isNotBlank())
            assertEquals("espacios sobrantes en la clave: '$en'", en.trim(), en)
        }
    }

    @Test fun `ejercicios distintos no comparten nombre en español`() {
        // Excepciones: nombres antiguos de las plantillas que son el mismo ejercicio que uno del catálogo
        val sameExercise = setOf("Seated Cable Row", "Lateral Raise")
        val dups = table.filterKeys { it !in sameExercise }.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue("traducciones repetidas: ${dups.keys}", dups.isEmpty())
    }

    @Test fun `sin traduccion se muestra el original (ejercicios personalizados)`() {
        assertEquals("Mi press raro", ExerciseNamesEs.display("Mi press raro"))
        assertEquals("Mi press raro", ExerciseDefinition(name = "Mi press raro", nameEs = "Mi press raro").displayName())
    }

    @Test fun `traducir no cambia el nombre guardado (referencia de historial y rutinas)`() {
        val d = def("Romanian Deadlift")
        assertEquals("Peso muerto rumano", d.displayName())
        assertEquals("Romanian Deadlift", d.name)
    }

    @Test fun `busca en español, sin tildes y por el nombre antiguo en ingles`() {
        val lat = def("Wide-Grip Lat Pulldown")
        assertTrue(lat.matchesQuery("jalón"))
        assertTrue(lat.matchesQuery("JALON al pecho"))
        assertTrue(lat.matchesQuery("lat pulldown"))
        assertFalse(lat.matchesQuery("sentadilla"))
        assertTrue(lat.matchesQuery("   ")) // vacío = todos
        assertTrue(def("Barbell Bench Press - Medium Grip").matchesQuery("press de banca"))
    }

    @Test fun `filtros por grupo en español o en ingles`() {
        assertTrue(def("Dumbbell Flyes", group = "Pecho").matchesFilters(listOf("Pecho")))
        assertTrue(def("X", muscles = "lats, biceps").matchesFilters(listOf("Dorsales")))
        assertFalse(def("Leg Press", group = "Piernas", muscles = "Cuádriceps").matchesFilters(listOf("Pecho")))
        assertTrue(def("Leg Press", group = "Piernas").matchesFilters(emptyList()))
    }

    @Test fun `grupo y equipo en español`() {
        assertEquals("Estiramientos", ExerciseNamesEs.group("Stretching"))
        assertEquals("Pecho", ExerciseNamesEs.group("Pecho"))
        assertEquals("Rodillo de espuma", ExerciseNamesEs.equipment("Foam Roller"))
    }
}
