package com.gymflow.synccore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Mismos casos que el servidor (contract/content-equality-cases.json): cliente y servidor deben coincidir en qué es "el mismo contenido". */
class ContentContractTest {
    @Test fun `la igualdad de contenido coincide con el servidor`() {
        val f = listOf(File("../contract/content-equality-cases.json"), File("contract/content-equality-cases.json")).first { it.exists() }
        val cases = Json.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases.map { it.jsonObject }) {
            val a = Rec("routines", "x", if (c["aDeleted"]!!.jsonPrimitive.boolean) 1L else null, c["a"] as JsonObject)
            val b = Rec("routines", "x", if (c["bDeleted"]!!.jsonPrimitive.boolean) 1L else null, c["b"] as JsonObject)
            assertEquals(c["same"]!!.jsonPrimitive.boolean, Content.same(a, b), c["name"]!!.jsonPrimitive.content)
        }
    }
}
