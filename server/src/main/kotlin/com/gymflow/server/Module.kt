package com.gymflow.server

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.io.File
import java.net.URI
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Quién hace la petición: la app (token de Firebase) o un panel vinculado (solo lectura). */
data class UserPrincipal(val uid: String, val email: String?, val panel: Boolean = false)

private const val MAX_CHANGES_PER_PUSH = 2000
private const val MAX_RECORD_BYTES = 64 * 1024
private const val MAX_PUSH_BYTES = 10L * 1024 * 1024
private const val MAX_CLOCK_SKEW_MS = 24L * 60 * 60 * 1000
private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun Application.module(config: AppConfig, database: Database, clock: Clock = Clock.systemUTC()) {
    val sync = SyncRepository(database)
    val pairing = PairingRepository(database, clock)
    val exporter = ExportService(sync, clock)

    install(DefaultHeaders)
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") }
    }
    install(Compression) { gzip { minimumSize(1024) } }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    if (config.corsOrigins.isNotEmpty()) install(CORS) {
        config.corsOrigins.forEach { origin ->
            val uri = URI(origin)
            allowHost(uri.authority, schemes = listOf(uri.scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Delete)
    }
    install(RateLimit) {
        // Frena la fuerza bruta de códigos de vinculación
        register(RateLimitName("pairing")) { rateLimiter(limit = 10, refillPeriod = 1.minutes) }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Petición no válida"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Error no controlado en ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Error interno"))
        }
    }

    val jwkProvider = JwkProviderBuilder(URI(config.jwksUrl).toURL())
        .cached(10, 12, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    install(Authentication) {
        // Tokens de Firebase Auth: firma de Google, emisor y audiencia = tu proyecto
        jwt("firebase") {
            realm = "gymflow"
            verifier(jwkProvider, "https://securetoken.google.com/${config.firebaseProjectId}") {
                withAudience(config.firebaseProjectId)
                acceptLeeway(60)
            }
            validate { credential ->
                credential.payload.subject?.takeIf { it.isNotBlank() }
                    ?.let { UserPrincipal(it, credential.payload.getClaim("email").asString()) }
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Token no válido o caducado")) }
        }
        bearer("panel") {
            realm = "gymflow"
            authenticate { credential ->
                withContext(Dispatchers.IO) { pairing.userForToken(credential.token) }?.let { UserPrincipal(it, null, panel = true) }
            }
        }
    }

    routing {
        get("/health") {
            val ok = withContext(Dispatchers.IO) { database.isHealthy() }
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, HealthResponse(if (ok) "ok" else "db_down"))
        }

        authenticate("firebase") {
            get("/api/me") {
                val user = call.user()
                call.respond(withContext(Dispatchers.IO) { sync.me(user.uid, user.email) })
            }

            post("/api/sync/push") {
                val user = call.user()
                if ((call.request.contentLength() ?: 0) > MAX_PUSH_BYTES) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Demasiados datos en una sola petición"))
                }
                val request = call.receive<PushRequest>()
                validate(request, clock.millis())
                call.respond(withContext(Dispatchers.IO) { sync.push(user.uid, user.email, request.changes) })
            }

            get("/api/sync/pull") {
                val user = call.user()
                val since = call.request.queryParameters["since"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 500
                call.respond(withContext(Dispatchers.IO) { sync.pull(user.uid, since, limit) })
            }

            post("/api/pairing/code") {
                val user = call.user()
                call.respond(withContext(Dispatchers.IO) { pairing.createCode(user.uid, user.email) })
            }

            delete("/api/panel-tokens") {
                val user = call.user()
                call.respond(RevokeResponse(withContext(Dispatchers.IO) { pairing.revokeAll(user.uid) }))
            }
        }

        // El panel vinculado solo puede leer la exportación
        authenticate("firebase", "panel", strategy = AuthenticationStrategy.FirstSuccessful) {
            get("/api/export") {
                val user = call.user()
                call.respond(withContext(Dispatchers.IO) { exporter.export(user.uid) })
            }
        }

        rateLimit(RateLimitName("pairing")) {
            post("/api/pairing/claim") {
                val request = call.receive<ClaimRequest>()
                val token = withContext(Dispatchers.IO) { pairing.claim(request.code, request.label) }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Código incorrecto o caducado"))
                call.respond(ClaimResponse(token))
            }
        }

        // Panel web compilado (en Docker: /app/static)
        config.staticDir?.let { staticFiles("/", File(it)) }
    }
}

private fun ApplicationCall.user(): UserPrincipal = principal<UserPrincipal>() ?: error("Ruta sin autenticación")

private fun validate(request: PushRequest, now: Long) {
    if (request.changes.size > MAX_CHANGES_PER_PUSH) {
        throw BadRequestException("Máximo $MAX_CHANGES_PER_PUSH cambios por petición")
    }
    request.changes.forEach { ch ->
        if (ch.table !in SYNC_TABLES) throw BadRequestException("Tabla desconocida: ${ch.table}")
        if (!UUID_RE.matches(ch.id)) throw BadRequestException("Id no válido: ${ch.id}")
        if (ch.updatedAt < 1 || ch.updatedAt > now + MAX_CLOCK_SKEW_MS) {
            throw BadRequestException("Fecha de modificación imposible en ${ch.table}/${ch.id}: revisa la hora del móvil")
        }
        if (ch.deletedAt != null && ch.deletedAt < 1) throw BadRequestException("deletedAt no válido en ${ch.table}/${ch.id}")
        if (ch.data.toString().length > MAX_RECORD_BYTES) throw BadRequestException("Registro demasiado grande: ${ch.table}/${ch.id}")
    }
}
