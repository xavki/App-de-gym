package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Traducción de instrucciones: correspondencia con el catálogo, sin huecos ni restos del original. */
class ExerciseInstructionsTest {

    private val names = ExerciseNamesEs.parse(File("src/main/assets/exercise_names_es.json").readText())
    private val instructions = ExerciseNamesEs.parse(File("src/main/assets/exercise_instructions_es.json").readText())
    private val review = ExerciseNamesEs.parse(File("src/main/assets/exercise_instructions_review_es.json").readText())

    /** Ejercicios del catálogo sin instrucciones en el original: no se inventa contenido. */
    private val withoutOriginal = setOf("Iron Cross", "One-Arm Kettlebell Swings", "Push Press", "Side Bridge", "Side Jackknife")

    @Test fun `todas las instrucciones con original estan traducidas y una sola vez`() {
        // 873 del catálogo menos las 5 que no traen instrucciones
        assertEquals(868, instructions.size)
        withoutOriginal.forEach { assertFalse("no debe tener traducción inventada: $it", it in instructions) }
        withoutOriginal.forEach { assertTrue("debe seguir en el catálogo: $it", it in names) }
    }

    @Test fun `cada traduccion corresponde a un ejercicio del catalogo por su nombre original`() {
        instructions.keys.forEach { assertTrue("no existe en el catálogo: $it", it in names) }
        // La clave es el nombre original (id estable), nunca el nombre traducido
        instructions.keys.forEach { assertFalse("clave traducida en vez de original: $it", it == names[it] && it.any { c -> c in "áéíóúñ" }) }
    }

    @Test fun `sin textos vacios, sin restos en ingles y con el formato de la app`() {
        val english = Regex("\b(the|your|you|with|and|repetitions)\b", RegexOption.IGNORE_CASE)
        instructions.forEach { (k, v) ->
            assertTrue("vacía o demasiado corta: $k", v.isNotBlank() && v.length >= 40)
            assertNull("resto en inglés en $k: ${english.find(v)?.value}", english.find(v))
            assertFalse("restos de HTML en $k", v.contains("li>") || v.contains("<"))
        }
    }

    @Test fun `avisos de revision solo para instrucciones traducidas y se muestran en la ficha`() {
        assertTrue(review.isNotEmpty())
        review.forEach { (k, v) ->
            assertTrue("aviso de un ejercicio sin traducción: $k", k in instructions)
            assertTrue("aviso vacío: $k", v.length > 20)
        }
        ExerciseNamesEs.loadInstructionsFrom(File("src/main/assets/exercise_instructions_es.json").readText())
        ExerciseNamesEs.loadReviewNotesFrom(File("src/main/assets/exercise_instructions_review_es.json").readText())
        val dudosa = ExerciseDefinition(name = "Neck Press", instructions = "Lie back on a flat bench.")
        assertTrue(dudosa.displayInstructions().startsWith("Túmbate"))
        assertTrue(dudosa.instructionsReviewNote()!!.startsWith("Potencialmente insegura"))
        val validada = ExerciseDefinition(name = "Plank", instructions = "x")
        assertNull(validada.instructionsReviewNote())
        // El original se conserva como respaldo: el catálogo no se modifica
        assertEquals("Lie back on a flat bench.", dudosa.instructions)
    }
}
