package com.gymflow

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException

/**
 * Por qué ha fallado «Entrar con Google».
 *
 * [code] es el ÚNICO identificador de diagnóstico que se muestra o se registra: un valor fijo de esta
 * lista. Nunca se usa el nombre de la clase, el tipo ni el mensaje de la excepción original.
 */
enum class GoogleSignInFailure(val code: String) {
    /** El usuario cerró el selector de cuentas. No es un error. */
    CANCELLED("G-CANCEL"),
    /** Google no devolvió ninguna credencial utilizable (no implica que falte una cuenta). */
    NO_CREDENTIAL("G-NOCRED"),
    /** Falta el servicio de credenciales de Google (Google Play Services). */
    PROVIDER_MISSING("G-PROVIDER"),
    /** El sistema no admite Credential Manager. */
    UNSUPPORTED("G-UNSUPPORTED"),
    /** El sistema interrumpió la operación; basta reintentar. */
    INTERRUPTED("G-INTERRUPTED"),
    /** Google devolvió una respuesta que la app no entiende o que está mal formada. */
    INVALID_RESPONSE("G-INVALID"),
    /** Cualquier otro fallo (incluye errores de configuración OAuth o de firma). */
    UNKNOWN("G-UNKNOWN"),
}

object GoogleSignInErrors {

    fun classify(error: Throwable): GoogleSignInFailure = when (error) {
        is GetCredentialCancellationException -> GoogleSignInFailure.CANCELLED
        is NoCredentialException -> GoogleSignInFailure.NO_CREDENTIAL
        is GetCredentialProviderConfigurationException -> GoogleSignInFailure.PROVIDER_MISSING
        is GetCredentialUnsupportedException -> GoogleSignInFailure.UNSUPPORTED
        is GetCredentialInterruptedException -> GoogleSignInFailure.INTERRUPTED
        else -> GoogleSignInFailure.UNKNOWN
    }

    /** Mensaje para el usuario, en español. */
    fun message(failure: GoogleSignInFailure): String = when (failure) {
        GoogleSignInFailure.CANCELLED ->
            "Has cancelado el inicio de sesión con Google."
        GoogleSignInFailure.NO_CREDENTIAL ->
            "Google no ha devuelto una credencial utilizable. Vuelve a intentarlo."
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

    /** Línea de log: solo el código fijo, nunca datos de la excepción. Es la que usa la app. */
    fun logMessage(failure: GoogleSignInFailure): String = "Google Sign-In: ${failure.code}"

    /** Código de diagnóstico: siempre uno de los valores fijos de [GoogleSignInFailure]. */
    fun diagnosticCode(failure: GoogleSignInFailure): String = failure.code

    /** Texto final para el usuario: el mensaje y, salvo en cancelaciones, el código fijo. */
    fun userText(failure: GoogleSignInFailure): String {
        val base = message(failure)
        return if (failure == GoogleSignInFailure.CANCELLED) base else "$base [${failure.code}]"
    }
}
