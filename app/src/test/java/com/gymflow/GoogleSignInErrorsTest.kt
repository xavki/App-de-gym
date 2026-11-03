package com.gymflow

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tratamiento de errores de «Entrar con Google» (sin móvil, sin Firebase, sin red). */
class GoogleSignInErrorsTest {

    private val secreto = "eyJhbGciOiJSUzI1NiJ9.TOKEN-SECRETO usuario@correo.com uid-12345"

    @Test fun `cancelar el selector se clasifica como cancelacion`() {
        val e = GetCredentialCancellationException("Activity is cancelled by the user")
        assertEquals(GoogleSignInFailure.CANCELLED, GoogleSignInErrors.classify(e))
    }

    @Test fun `sin credenciales se clasifica como credencial no devuelta, no como falta de cuenta`() {
        val e = NoCredentialException("No credentials available")
        assertEquals(GoogleSignInFailure.NO_CREDENTIAL, GoogleSignInErrors.classify(e))
    }

    @Test fun `el mensaje de NoCredential es neutral y no afirma que falte una cuenta`() {
        val m = GoogleSignInErrors.message(GoogleSignInFailure.NO_CREDENTIAL)
        assertEquals("Google no ha devuelto una credencial utilizable. Vuelve a intentarlo.", m)
        assertFalse(m.contains("cuenta", ignoreCase = true))
        assertFalse(m.contains("Añade", ignoreCase = true))
    }

    @Test fun `falta de proveedor y sistema no compatible`() {
        assertEquals(
            GoogleSignInFailure.PROVIDER_MISSING,
            GoogleSignInErrors.classify(GetCredentialProviderConfigurationException("x"))
        )
        assertEquals(
            GoogleSignInFailure.UNSUPPORTED,
            GoogleSignInErrors.classify(GetCredentialUnsupportedException("x"))
        )
    }

    @Test fun `interrupcion se clasifica como interrumpido`() {
        assertEquals(
            GoogleSignInFailure.INTERRUPTED,
            GoogleSignInErrors.classify(GetCredentialInterruptedException("x"))
        )
    }

    @Test fun `errores desconocidos y de otro tipo caen en UNKNOWN`() {
        assertEquals(
            GoogleSignInFailure.UNKNOWN,
            GoogleSignInErrors.classify(GetCredentialUnknownException("x"))
        )
        assertEquals(GoogleSignInFailure.UNKNOWN, GoogleSignInErrors.classify(IllegalStateException("x")))
    }

    @Test fun `todos los mensajes estan en espanol y no estan vacios`() {
        for (f in GoogleSignInFailure.values()) {
            val m = GoogleSignInErrors.message(f)
            assertTrue("mensaje vacío para $f", m.isNotBlank())
            assertFalse("mensaje en inglés para $f: $m", m.contains("No credentials available"))
        }
    }

    @Test fun `los codigos de diagnostico son valores fijos, unicos y con formato permitido`() {
        val codes = GoogleSignInFailure.values().map { GoogleSignInErrors.diagnosticCode(it) }
        assertEquals(codes.size, codes.toSet().size)
        for (c in codes) assertTrue("código no permitido: $c", Regex("^G-[A-Z]+$").matches(c))
    }

    @Test fun `ni el codigo ni el texto al usuario incluyen datos de la excepcion original`() {
        val excepciones = listOf(
            NoCredentialException(secreto),
            GetCredentialCancellationException(secreto),
            GetCredentialProviderConfigurationException(secreto),
            GetCredentialUnsupportedException(secreto),
            GetCredentialInterruptedException(secreto),
            GetCredentialUnknownException(secreto),
            IllegalStateException(secreto),
        )
        for (e in excepciones) {
            val f = GoogleSignInErrors.classify(e)
            val texto = GoogleSignInErrors.userText(f)
            for (prohibido in listOf("TOKEN-SECRETO", "usuario@correo.com", "uid-12345", "eyJ", "Exception")) {
                assertFalse("$prohibido filtrado en: $texto", texto.contains(prohibido))
            }
            assertFalse(texto.contains("@"))
        }
    }

    @Test fun `la cancelacion no muestra codigo tecnico`() {
        val text = GoogleSignInErrors.userText(GoogleSignInFailure.CANCELLED)
        assertEquals(GoogleSignInErrors.message(GoogleSignInFailure.CANCELLED), text)
    }

    @Test fun `un fallo real muestra mensaje y codigo fijo`() {
        val text = GoogleSignInErrors.userText(GoogleSignInFailure.NO_CREDENTIAL)
        assertTrue(text.startsWith(GoogleSignInErrors.message(GoogleSignInFailure.NO_CREDENTIAL)))
        assertTrue(text.endsWith("[G-NOCRED]"))
    }
}
