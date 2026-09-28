package com.gymflow.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File

/**
 * Servidor de DESARROLLO (solo en el código de tests, nunca en producción):
 * PostgreSQL embebido + claves de prueba, y GET /dev/token?uid=... para obtener
 * un token válido sin Firebase. Lo usan las pruebas de extremo a extremo
 * (la app en el emulador llega a este PC por http://10.0.2.2:8080).
 *
 *   ./gradlew runDev
 */
fun main() {
    val panel = File("../panel/dist").takeIf { File(it, "index.html").exists() }
    val config = TestEnv.config.copy(port = 8080, staticDir = panel?.absolutePath)
    println("GymFlow dev server en http://localhost:8080 (panel: ${panel?.absolutePath ?: "no compilado"})")
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, TestEnv.database)
        routing {
            get("/dev/token") {
                val uid = call.request.queryParameters["uid"] ?: "dev-user"
                call.respondText(TestEnv.keys.token(uid, email = "$uid@example.com"))
            }
        }
    }.start(wait = true)
}
