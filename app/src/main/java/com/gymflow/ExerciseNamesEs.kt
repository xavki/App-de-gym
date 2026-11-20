package com.gymflow

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * Nombres de ejercicios en español.
 *
 * El catálogo (Firestore → Room) guarda el nombre original en inglés, y ese nombre es la
 * referencia estable que usan los entrenos y rutinas guardados (`exerciseName`). No se cambia:
 * la traducción solo se aplica al MOSTRAR y al BUSCAR, así que el historial y la sincronización
 * no se tocan. Tabla: assets/exercise_names_es.json (873 del catálogo + nombres de plantillas).
 * Los ejercicios personalizados se muestran tal cual los escribió el usuario.
 */
object ExerciseNamesEs {

    @Volatile private var names: Map<String, String> = emptyMap()
    /** Instrucciones traducidas (assets/exercise_instructions_es.json); el resto se muestra en el original. */
    @Volatile private var instructions: Map<String, String> = emptyMap()
    /** Instrucciones del original dudosas, anticuadas o con posible riesgo (assets/exercise_instructions_review_es.json). */
    @Volatile private var reviewNotes: Map<String, String> = emptyMap()

    fun load(context: Context) {
        if (names.isNotEmpty()) return
        names = try {
            parse(context.assets.open("exercise_names_es.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { emptyMap() }
        instructions = try {
            parse(context.assets.open("exercise_instructions_es.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { emptyMap() }
        reviewNotes = try {
            parse(context.assets.open("exercise_instructions_review_es.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { emptyMap() }
    }

    fun loadInstructionsFrom(json: String) { instructions = parse(json) }
    fun loadReviewNotesFrom(json: String) { reviewNotes = parse(json) }

    /** Motivo por el que la instrucción traducida está pendiente de revisión; null si no lo está. */
    fun reviewNote(name: String): String? = reviewNotes[name]

    /** Instrucciones en español si hay traducción revisada; si no, null (se muestra el original). */
    fun instructions(name: String): String? = instructions[name]

    /** Para los tests (y por si se quisiera cargar desde otro sitio). */
    fun loadFrom(json: String) { names = parse(json) }

    fun parse(json: String): Map<String, String> =
        Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** Nombre en español de un ejercicio guardado por su nombre original; si no hay traducción, el mismo. */
    fun display(name: String): String = names[name] ?: name

    fun hasTranslation(name: String) = name in names

    private val groups = mapOf("Stretching" to "Estiramientos")
    private val equipment = mapOf("Foam Roller" to "Rodillo de espuma")
    fun group(name: String): String = groups[name] ?: name
    fun equipment(name: String): String = equipment[name] ?: name
}

/** Nombre a mostrar: el español del catálogo (o de la tabla) y, si no hay, el original. */
fun ExerciseDefinition.displayName(): String = nameEs?.takeIf { it.isNotBlank() } ?: ExerciseNamesEs.display(name)

/** Minúsculas y sin tildes: "Jalón" encuentra "jalon" y al revés. */
fun normalizeForSearch(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Coincide con el texto buscado en español o con el nombre antiguo en inglés. */
fun ExerciseDefinition.matchesQuery(query: String): Boolean {
    val q = normalizeForSearch(query.trim())
    if (q.isEmpty()) return true
    return normalizeForSearch(displayName()).contains(q) || normalizeForSearch(name).contains(q)
}

/** Instrucciones a mostrar: traducción revisada, la del catálogo en español o el texto original. */
fun ExerciseDefinition.displayInstructions(): String =
    ExerciseNamesEs.instructions(name) ?: instructionsEs?.takeIf { it.isNotBlank() } ?: instructions

/** Aviso a mostrar bajo las instrucciones traducidas que no son una recomendación validada. */
fun ExerciseDefinition.instructionsReviewNote(): String? =
    if (ExerciseNamesEs.instructions(name) != null) ExerciseNamesEs.reviewNote(name) else null
