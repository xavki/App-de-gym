package com.gymflow

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException

/** Por qué ha fallado «Entrar con Google». Sin dependencias de Android: se prueba en la JVM. */
enum class GoogleSignInFailure {
    /** El usuario cerró el selector de cuentas. No es un error. */
    CANCELLED,
    /** No hay cuentas de Google utilizables en el móvil (sin cuenta o con la sesión caducada). */
    NO_ACCOUNT,
    /** Falta el servicio de credenciales de Google (Google Play Services). */
    PROVIDER_MISSING,
    /** El sistema no admite Credential Manager. */
    UNSUPPORTED,
    /** El sistema interrumpió la operación; basta reintentar. */
    INTERRUPTED,
    /** Google devolvió una respuesta que la app no entiende. */
    INVALID_RESPONSE,
    /** Cualquier otro fallo (incluye errores de configuración OAuth o de firma). */
    UNKNOWN,
}

object GoogleSignInErrors {

    fun classify(error: Throwable): GoogleSignInFailure = when (error) {
        is GetCredentialCancellationException -> GoogleSignInFailure.CANCELLED
        is NoCredentialException -> GoogleSignInFailure.NO_ACCOUNT
        is GetCredentialProviderConfigurationException -> GoogleSignInFailure.PROVIDER_MISSING
        is GetCredentialUnsupportedException -> GoogleSignInFailure.UNSUPPORTED
        is GetCredentialInterruptedException -> GoogleSignInFailure.INTERRUPTED
        else -> GoogleSignInFailure.UNKNOWN
    }

    /** Mensaje para el usuario, en español. */
    fun message(failure: GoogleSignInFailure): String = when (failure) {
        GoogleSignInFailure.CANCELLED ->
            "Has cancelado el inicio de sesión con Google."
        GoogleSignInFailure.NO_ACCOUNT ->
            "No hay ninguna cuenta de Google disponible en este móvil. " +
                "Añade una en Ajustes → Cuentas, o vuelve a iniciar sesión en ella, y prueba de nuevo."
        GoogleSignInFailure.PROVIDER_MISSING ->
            "Falta el servicio de credenciales de Google. " +
                "Actualiza Google Play Services desde Play Store y prueba de nuevo."
        GoogleSignInFailure.UNSUPPORTED ->
            "Este móvil no admite el inicio de sesión con Google. Usa tu correo y contraseña."
        GoogleSignInFailure.INTERRUPTED ->
            "El inicio de sesión se interrumpió. Inténtalo de nuevo."
        GoogleSignInFailure.INVALID_RESPONSE ->
            "Google devolvió una respuesta que la app no reconoce. " +
                "Actualiza Google Play Services y prueba de nuevo."
        GoogleSignInFailure.UNKNOWN ->
            "No se pudo iniciar sesión con Google. Inténtalo de nuevo; si sigue fallando, " +
                "revisa que la firma de la app esté registrada en Firebase."
    }

    /**
     * Código corto para diagnosticar sin ordenador. Solo contiene el nombre de la clase de la
     * excepción y su tipo: nunca el mensaje, el token, el correo ni el identificador de usuario.
     */
    fun diagnosticCode(error: Throwable): String {
        val name = error.javaClass.simpleName.ifEmpty { "Error" }
        return if (error is GetCredentialException) "$name/${error.type}" else name
    }

    /** Texto final que se muestra al usuario: el mensaje y, si no es una cancelación, el código. */
    fun userText(failure: GoogleSignInFailure, error: Throwable?): String {
        val base = message(failure)
        return if (failure == GoogleSignInFailure.CANCELLED || error == null) base
        else "$base [${diagnosticCode(error)}]"
    }
}
