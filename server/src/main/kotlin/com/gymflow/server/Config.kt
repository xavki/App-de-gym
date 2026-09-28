package com.gymflow.server

/** Claves públicas con las que Google firma los tokens de Firebase Auth (formato JWKS). */
const val FIREBASE_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"

data class DbConfig(val url: String, val user: String, val password: String)

/** Toda la configuración sale de variables de entorno (ver deploy/.env.example). */
data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val firebaseProjectId: String,
    val jwksUrl: String,
    val corsOrigins: List<String>,
    val staticDir: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Falta la variable de entorno $name")
            return AppConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                db = DbConfig(
                    url = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/gymflow",
                    user = env["DATABASE_USER"] ?: "gymflow",
                    password = required("DATABASE_PASSWORD"),
                ),
                firebaseProjectId = required("FIREBASE_PROJECT_ID"),
                jwksUrl = env["FIREBASE_JWKS_URL"] ?: FIREBASE_JWKS_URL,
                corsOrigins = env["CORS_ORIGINS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                staticDir = env["STATIC_DIR"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
