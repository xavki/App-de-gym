package com.gymflow.sync

import com.gymflow.synccore.PullResponse
import com.gymflow.synccore.PushChange
import com.gymflow.synccore.PushRequest
import com.gymflow.synccore.PushResponse
import com.gymflow.synccore.RemoteApi
import com.gymflow.synccore.RemoteException
import com.gymflow.synccore.ServerUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// ─── Protocolo v2 (los tipos del intercambio viven en :syncCore, compartidos con las pruebas) ───────────────────────

@Serializable data class PairingCodeResponse(val code: String, val expiresAt: Long)
@Serializable data class RevokeResponse(val revoked: Int)
@Serializable private data class ErrorResponse(val error: String)

/** Error HTTP con el mensaje que devuelve el servidor. 401 = sesión caducada. */
class SyncHttpException(val status: Int, message: String) : IOException(message)

val syncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Cliente HTTP del servidor propio. [token] da el ID token de Firebase (con `forceRefresh = true` pide uno nuevo si el servidor
 * responde 401). HTTPS obligatorio (HTTP solo hacia el PC del emulador en depuración); sin redirecciones, para que el token
 * nunca viaje a otro destino. Los errores se traducen a [RemoteException] (red, servidor, sesión, rechazo, identidad).
 */
class HttpSyncApi(
    baseUrl: String,
    private val token: suspend (forceRefresh: Boolean) -> String,
    allowInsecureLocal: Boolean = false,
    private val client: OkHttpClient = defaultClient,
) : RemoteApi {

    private val base: String = ServerUrl.validate(baseUrl, allowInsecureLocal).let {
        require(it.ok && !it.url.isNullOrEmpty()) { "Dirección de servidor no válida: ${it.error}" }
        it.url!!
    }

    override suspend fun push(changes: List<PushChange>): PushResponse =
        call("POST", "/api/sync/push", syncJson.encodeToString(PushRequest.serializer(), PushRequest(changes)))
            .let { syncJson.decodeFromString(PushResponse.serializer(), it) }

    override suspend fun pull(since: Long, limit: Int): PullResponse =
        call("GET", "/api/sync/pull?since=$since&limit=$limit", null)
            .let { syncJson.decodeFromString(PullResponse.serializer(), it) }

    suspend fun pairingCode(): PairingCodeResponse =
        call("POST", "/api/pairing/code", "{}").let { syncJson.decodeFromString(PairingCodeResponse.serializer(), it) }

    suspend fun revokePanels(): RevokeResponse =
        call("DELETE", "/api/panel-tokens", null).let { syncJson.decodeFromString(RevokeResponse.serializer(), it) }

    private suspend fun call(method: String, path: String, body: String?): String = try {
        try {
            execute(method, path, body, bearer(false))
        } catch (e: SyncHttpException) {
            if (e.status != 401) throw e
            execute(method, path, body, bearer(true)) // token caducado: uno nuevo y reintento
        }
    } catch (e: RemoteException) {
        throw e
    } catch (e: SyncHttpException) {
        throw RemoteException(
            when {
                e.status == 401 -> RemoteException.Kind.AUTH
                e.status == 403 -> RemoteException.Kind.IDENTITY
                e.status == 408 || e.status == 429 || e.status >= 500 -> RemoteException.Kind.SERVER
                else -> RemoteException.Kind.REJECTED
            },
            "HTTP ${e.status}",
        )
    } catch (e: IOException) {
        throw RemoteException(RemoteException.Kind.NETWORK, "Sin conexión con el servidor")
    }

    private suspend fun bearer(force: Boolean): String = try {
        token(force)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException(RemoteException.Kind.AUTH, "Sesión no disponible")
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
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
