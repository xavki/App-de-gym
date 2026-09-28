package com.gymflow.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.io.Closeable
import java.sql.Connection

/** Pool de conexiones + migraciones (src/main/resources/db/migration) al arrancar. */
class Database(config: DbConfig) : Closeable {

    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = 5
        poolName = "gymflow"
    })

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    /** Ejecuta [block] en una transacción: commit si termina bien, rollback si lanza. */
    fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    fun isHealthy(): Boolean = runCatching {
        dataSource.connection.use { it.createStatement().use { st -> st.execute("SELECT 1") } }
    }.isSuccess

    override fun close() = dataSource.close()
}
