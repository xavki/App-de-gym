package com.gymflow.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfig.fromEnv()
    val database = Database(config.db)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, database)
    }.start(wait = true)
}
