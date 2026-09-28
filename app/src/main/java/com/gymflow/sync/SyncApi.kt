package com.gymflow.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ─── Protocolo (mismo formato que server/.../Models.kt y contract/) ──────────

@Serializable
data class Change(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
)

@Serializable data class PushRequest(val changes: List<Change>)
@Serializable data class PushResponse(val accepted: Int, val ignored: Int)

@Serializable
data class PulledChange(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
    val version: Long,
)

@Serializable
data class PullResponse(val changes: List<PulledChange>, val cursor: Long, val hasMore: Boolean, val reset: Boolean = false)

@Serializable data class PairingCodeResponse(val code: String, val expiresAt: Long)
@Serializable data class RevokeResponse(val revoked: Int)
@Serializable private data class ErrorResponse(val error: String)

/** Error HTTP con el mensaje que devuelve el servidor. 401 = sesión caducada. */
class SyncHttpException(val status: Int, message: String) : IOException(message)

interface SyncApi {
    suspend fun push(changes: List<Change>): PushResponse
    suspend fun pull(since: Long, limit: Int = 500): PullResponse
    suspend fun pairingCode(): PairingCodeResponse
    suspend fun revokePanels(): RevokeResponse
}

val syncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Cliente HTTP del servidor propio. [token] da el ID token de Firebase; con
 * `forceRefresh = true` pide uno nuevo (se usa si el servidor responde 401).
 */
class HttpSyncApi(
    baseUrl: String,
    private val token: suspend (forceRefresh: Boolean) -> String,
    private val client: OkHttpClient = defaultClient,
) : SyncApi {

    private val base = baseUrl.trimEnd('/')

    override suspend fun push(changes: List<Change>): PushResponse =
        call("POST", "/api/sync/push", syncJson.encodeToString(PushRequest.serializer(), PushRequest(changes)))
            .let { syncJson.decodeFromString(PushResponse.serializer(), it) }

    override suspend fun pull(since: Long, limit: Int): PullResponse =
        call("GET", "/api/sync/pull?since=$since&limit=$limit", null)
            .let { syncJson.decodeFromString(PullResponse.serializer(), it) }

    override suspend fun pairingCode(): PairingCodeResponse =
        call("POST", "/api/pairing/code", "{}").let { syncJson.decodeFromString(PairingCodeResponse.serializer(), it) }

    override suspend fun revokePanels(): RevokeResponse =
        call("DELETE", "/api/panel-tokens", null).let { syncJson.decodeFromString(RevokeResponse.serializer(), it) }

    private suspend fun call(method: String, path: String, body: String?): String {
        return try {
            execute(method, path, body, token(false))
        } catch (e: SyncHttpException) {
            if (e.status != 401) throw e
            execute(method, path, body, token(true)) // token caducado: uno nuevo y reintento
        }
    }

    private suspend fun execute(method: String, path: String, body: String?, bearer: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer $bearer")
                .method(method, body?.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = runCatching { syncJson.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
                    throw SyncHttpException(res.code, msg ?: "HTTP ${res.code}")
                }
                text
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
