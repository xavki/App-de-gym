package com.gymflow

/** Configuración fija del inicio de sesión con Google. */
object GoogleSignInConfig {
    /** Cliente OAuth web (client_type 3 de google-services.json), el mismo que usaba la app. */
    const val WEB_CLIENT_ID = "961115677843-no9vf5lk0g2fdbs2igir8u5u4fkee5l2.apps.googleusercontent.com"
}

/** Validación de lo que devuelve Credential Manager antes de pasárselo a Firebase. Sin dependencias de Android. */
object GoogleCredentialCheck {
    /** ¿La credencial es del tipo esperado («Google ID token»)? */
    fun isExpectedType(actual: String?, expected: String): Boolean = actual != null && actual == expected

    /** Un ID token de Google es un JWT: tres segmentos no vacíos separados por puntos. */
    fun isUsableToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val parts = token.split('.')
        return parts.size == 3 && parts.all { it.isNotEmpty() }
    }
}

/** Qué se sabe de un fallo de Firebase Auth. Deliberadamente SIN el mensaje de la excepción. */
data class FirebaseErrorInfo(val className: String, val errorCode: String?)

enum class FirebaseSignInFailure(val code: String) {
    NETWORK("F-NET"),
    INVALID_CREDENTIAL("F-CRED"),
    ACCOUNT_DISABLED("F-DISABLED"),
    ACCOUNT_CONFLICT("F-CONFLICT"),
    TOO_MANY_REQUESTS("F-LIMIT"),
    UNKNOWN("F-UNKNOWN"),
}

object FirebaseSignInErrors {

    fun classify(info: FirebaseErrorInfo?): FirebaseSignInFailure {
        if (info == null) return FirebaseSignInFailure.UNKNOWN
        return when {
            info.className.endsWith("FirebaseNetworkException") -> FirebaseSignInFailure.NETWORK
            info.className.endsWith("FirebaseTooManyRequestsException") -> FirebaseSignInFailure.TOO_MANY_REQUESTS
            else -> when (info.errorCode) {
                "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_CUSTOM_TOKEN", "ERROR_USER_TOKEN_EXPIRED" ->
                    FirebaseSignInFailure.INVALID_CREDENTIAL
                "ERROR_USER_DISABLED" -> FirebaseSignInFailure.ACCOUNT_DISABLED
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_EMAIL_ALREADY_IN_USE",
                "ERROR_CREDENTIAL_ALREADY_IN_USE" -> FirebaseSignInFailure.ACCOUNT_CONFLICT
                "ERROR_TOO_MANY_REQUESTS" -> FirebaseSignInFailure.TOO_MANY_REQUESTS
                else -> FirebaseSignInFailure.UNKNOWN
            }
        }
    }

    fun message(failure: FirebaseSignInFailure): String = when (failure) {
        FirebaseSignInFailure.NETWORK ->
            "No se pudo conectar con el servidor. Revisa tu conexión a Internet y vuelve a intentarlo."
        FirebaseSignInFailure.INVALID_CREDENTIAL ->
            "No se pudo validar tu sesión de Google. Vuelve a intentarlo."
        FirebaseSignInFailure.ACCOUNT_DISABLED ->
            "Esta cuenta está deshabilitada."
        FirebaseSignInFailure.ACCOUNT_CONFLICT ->
            "Ya existe una cuenta con otro método de acceso para este usuario."
        FirebaseSignInFailure.TOO_MANY_REQUESTS ->
            "Demasiados intentos. Espera unos minutos y vuelve a intentarlo."
        FirebaseSignInFailure.UNKNOWN ->
            "No se pudo completar el inicio de sesión. Inténtalo de nuevo."
    }

    fun userText(failure: FirebaseSignInFailure): String = "${message(failure)} [${failure.code}]"
}

/** Política de sesión de arranque (extraída tal cual de MainActivity.onCreate para poder probarla). */
object SessionPolicy {
    /**
     * Con sesión de Firebase pero sin «recordarme» se cierra al arrancar; con «recordarme» la sesión
     * se recupera y la app abre directamente la pantalla de inicio.
     */
    fun shouldSignOutAtStart(hasUser: Boolean, rememberMe: Boolean): Boolean = hasUser && !rememberMe
}
