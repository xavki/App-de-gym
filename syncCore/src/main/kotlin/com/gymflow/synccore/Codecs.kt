package com.gymflow.synccore

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Serialización de conflictos para guardarlos en la base local (sobreviven a reinicios). */
object ConflictCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(r: Rec): JsonObject = buildJsonObject {
        put("table", r.table); put("id", r.id); put("rev", r.rev); put("dirty", r.dirty)
        r.deletedAt?.let { put("deletedAt", it) }
        put("data", r.data)
    }

    private fun rec(o: JsonObject): Rec = Rec(
        o["table"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.content, o["deletedAt"]?.jsonPrimitive?.longOrNull,
        o["data"]!!.jsonObject, o["rev"]!!.jsonPrimitive.long, o["dirty"]!!.jsonPrimitive.boolean,
    )

    fun encode(g: ConflictGroup): String = buildJsonObject {
        put("table", g.root.table); put("id", g.root.id); put("label", g.label); put("reason", g.reason.name)
        put("detectedAt", g.detectedAt); put("complete", g.complete)
        put("items", JsonArray(g.items.map { i ->
            buildJsonObject {
                put("table", i.table); put("id", i.id)
                put("local", i.local?.let { rec(it) } ?: JsonNull)
                put("remote", i.remote?.let { json.encodeToJsonElement(RemoteRecord.serializer(), it) } ?: JsonNull)
            }
        }))
    }.toString()

    fun decode(text: String): ConflictGroup {
        val o = json.parseToJsonElement(text).jsonObject
        return ConflictGroup(
            root = Key(o["table"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.content),
            label = o["label"]!!.jsonPrimitive.content,
            items = o["items"]!!.jsonArray.map { e ->
                val i = e.jsonObject
                ConflictItem(
                    i["table"]!!.jsonPrimitive.content, i["id"]!!.jsonPrimitive.content,
                    (i["local"] as? JsonObject)?.let { rec(it) },
                    (i["remote"] as? JsonObject)?.let { json.decodeFromJsonElement(RemoteRecord.serializer(), it) },
                )
            },
            reason = ConflictReason.valueOf(o["reason"]!!.jsonPrimitive.content),
            detectedAt = o["detectedAt"]!!.jsonPrimitive.long,
            complete = o["complete"]!!.jsonPrimitive.boolean,
        )
    }
}

/** Validación de la dirección del servidor: HTTPS obligatorio; HTTP solo hacia el PC del emulador/localhost y solo si se permite (depuración). */
object ServerUrl {
    data class Result(val url: String?, val error: String?) { val ok get() = error == null }

    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    fun validate(input: String, allowInsecureLocal: Boolean = false): Result {
        val text = input.trim()
        if (text.isEmpty()) return Result("", null)                       // vacío = sincronización desactivada
        if (text.length > 200) return Result(null, "La dirección es demasiado larga")
        val u = try { URI(text) } catch (e: Exception) { return Result(null, "La dirección no es válida") }
        val scheme = u.scheme?.lowercase() ?: return Result(null, "Falta https://")
        val host = u.host?.lowercase() ?: return Result(null, "Falta el nombre del servidor")
        if (u.userInfo != null) return Result(null, "No se admiten usuario ni contraseña en la dirección")
        if (u.rawQuery != null || u.rawFragment != null) return Result(null, "La dirección no debe llevar parámetros")
        if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) return Result(null, "Indica solo el dominio, sin ruta")
        if (u.port != -1 && u.port !in 1..65535) return Result(null, "Puerto no válido")
        return when {
            scheme == "https" -> Result("https://" + host + (if (u.port == -1) "" else ":${u.port}"), null)
            scheme == "http" && allowInsecureLocal && host in LOCAL_HOSTS -> Result("http://" + host + (if (u.port == -1) "" else ":${u.port}"), null)
            scheme == "http" -> Result(null, "Debe empezar por https://")
            else -> Result(null, "Esquema no admitido")
        }
    }
}
