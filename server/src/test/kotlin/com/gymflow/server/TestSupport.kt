package com.gymflow.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import java.util.UUID

const val TEST_PROJECT = "gymflow-test"

/** Reloj que los tests pueden adelantar (caducidad de códigos). */
class MutableClock(var now: Instant = Instant.now()) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant() = now
}

/** Claves RSA de prueba publicadas como JWKS en un archivo (hace el papel de Google). */
class TestKeys(val kid: String = "test-key") {
    val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val jwksFile: File = Files.createTempFile("jwks", ".json").toFile().apply {
        deleteOnExit()
        val pub = pair.public as RSAPublicKey
        fun b64(n: BigInteger) = Base64.getUrlEncoder().withoutPadding().encodeToString(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        writeText("""{"keys":[{"kty":"RSA","kid":"$kid","use":"sig","alg":"RS256","n":"${b64(pub.modulus)}","e":"${b64(pub.publicExponent)}"}]}""")
    }

    /** Token con la forma de un ID token de Firebase. */
    fun token(
        uid: String,
        email: String? = null,
        project: String = TEST_PROJECT,
        expiresInSec: Long = 3600,
        signWith: KeyPair = pair,
    ): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withKeyId(kid)
            .withIssuer("https://securetoken.google.com/$project")
            .withAudience(project)
            .withSubject(uid)
            .withClaim("email", email)
            .withIssuedAt(Date(now - 1000))
            .withExpiresAt(Date(now + expiresInSec * 1000))
            .sign(Algorithm.RSA256(signWith.public as RSAPublicKey, signWith.private as RSAPrivateKey))
    }
}

/** PostgreSQL embebido compartido por todos los tests; cada test usa uids aleatorios. */
object TestEnv {
    val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
    val keys by lazy { TestKeys() }
    val config by lazy {
        AppConfig(
            port = 0,
            db = DbConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"),
            firebaseProjectId = TEST_PROJECT,
            jwksUrl = keys.jwksFile.toURI().toString(),
            corsOrigins = emptyList(),
            staticDir = null,
        )
    }
    val database by lazy { Database(config.db) }
}

fun ApplicationTestBuilder.setUp(clock: Clock = Clock.systemUTC(), config: AppConfig = TestEnv.config): HttpClient {
    application { module(config, TestEnv.database, clock) }
    return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}

fun newUid() = "user-" + UUID.randomUUID()
fun uuid() = UUID.randomUUID().toString()

fun change(table: String, id: String = uuid(), updatedAt: Long = 1000, deletedAt: Long? = null, data: JsonObject = buildJsonObject { put("id", id) }) =
    Change(table, id, updatedAt, deletedAt, data)
