package com.gymflow.sync

import com.gymflow.data.ExerciseEntity
import com.gymflow.data.WorkoutEntity
import com.gymflow.data.WorkoutExerciseEntity
import com.gymflow.data.WorkoutSetEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Contrato app ↔ servidor: lo que la app envía de cada fila debe coincidir con
 * contract/sync-push-example.json, el mismo archivo que usan los tests del servidor.
 */
class SyncContractTest {

    private val t = 1790610616000L

    private val example: Map<String, JsonObject> by lazy {
        val file = listOf(File("../contract/sync-push-example.json"), File("contract/sync-push-example.json")).first { it.exists() }
        syncJson.decodeFromString(PushRequest.serializer(), file.readText()).changes.associate { it.id to it.data }
    }

    @Test fun customExerciseMatchesContract() = check(
        "87766a20-93bc-403b-a7d3-b5b66d3ab9e1",
        SyncEngine.encode(ExerciseEntity.serializer(), ExerciseEntity(
            id = "87766a20-93bc-403b-a7d3-b5b66d3ab9e1", userId = "user-1", name = "Bench Press", nameEs = "Bench Press",
            mainGroup = "Pecho", musclesUsed = "", equipment = "", instructions = "", instructionsEs = null,
            subCategory = null, difficulty = null, imageUrl = null, gifUrl = null, notes = "", isCustom = true,
            createdAt = t, updatedAt = t
        ))
    )

    @Test fun workoutMatchesContract() = check(
        "59eb4793-1c2b-4401-8949-6e1c15497a2a",
        SyncEngine.encode(WorkoutEntity.serializer(), WorkoutEntity(
            id = "59eb4793-1c2b-4401-8949-6e1c15497a2a", userId = "user-1", routineId = null, name = "Push, heavy",
            startedAt = t, durationSeconds = 0, bodyweightKg = null, notes = "", createdAt = t, updatedAt = t
        ))
    )

    @Test fun workoutExerciseMatchesContract() = check(
        "3b144428-4e5c-3d98-8a1a-f4e155d69710",
        SyncEngine.encode(WorkoutExerciseEntity.serializer(), WorkoutExerciseEntity(
            id = "3b144428-4e5c-3d98-8a1a-f4e155d69710", workoutId = "59eb4793-1c2b-4401-8949-6e1c15497a2a",
            exerciseId = "87766a20-93bc-403b-a7d3-b5b66d3ab9e1", exerciseName = "Bench Press", mainGroup = "Pecho",
            position = 0, notes = "", createdAt = t, updatedAt = t
        ))
    )

    @Test fun workoutSetMatchesContract() = check(
        "1bd3cc74-39a6-34f9-8299-63b7299669fa",
        SyncEngine.encode(WorkoutSetEntity.serializer(), WorkoutSetEntity(
            id = "1bd3cc74-39a6-34f9-8299-63b7299669fa", workoutExerciseId = "3b144428-4e5c-3d98-8a1a-f4e155d69710",
            position = 0, setType = "NORMAL", weightKg = 60.0, reps = 10, timeSeconds = 0, rpe = 9.0, completed = true,
            createdAt = t, updatedAt = t
        ))
    )

    @Test fun dirtyFlagNeverTravels() {
        val data = SyncEngine.encode(WorkoutEntity.serializer(), WorkoutEntity(
            id = "x", userId = "u", routineId = null, name = "", startedAt = 0, durationSeconds = 0,
            bodyweightKg = null, notes = "", createdAt = 0, updatedAt = 0, dirty = true
        ))
        assertFalse(data.containsKey("dirty"))
    }

    @Test fun decodingIgnoresUnknownFieldsFromNewerVersions() {
        val data = example.getValue("59eb4793-1c2b-4401-8949-6e1c15497a2a").toMutableMap()
        data["campoNuevo"] = syncJson.parseToJsonElement("\"x\"")
        val entity = syncJson.decodeFromJsonElement(WorkoutEntity.serializer(), JsonObject(data))
        assertEquals("Push, heavy", entity.name)
        assertEquals(true, entity.dirty) // el valor por defecto; el motor lo pone a false al aplicar
    }

    private fun check(id: String, actual: JsonObject) {
        assertEquals(example.getValue(id), syncJson.parseToJsonElement(actual.toString()).jsonObject)
    }
}
