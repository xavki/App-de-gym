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

    @Test fun `cancelar el selector se clasifica como cancelacion`() {
        val e = GetCredentialCancellationException("Activity is cancelled by the user")
        assertEquals(GoogleSignInFailure.CANCELLED, GoogleSignInErrors.classify(e))
    }

    @Test fun `sin credenciales se clasifica como sin cuenta`() {
        val e = NoCredentialException("No credentials available")
        assertEquals(GoogleSignInFailure.NO_ACCOUNT, GoogleSignInErrors.classify(e))
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

    @Test fun `el codigo de diagnostico no incluye el mensaje de la excepcion`() {
        val secreto = "eyJhbGciOiJSUzI1NiJ9.TOKEN-SECRETO usuario@correo.com"
        val e = NoCredentialException(secreto)
        val code = GoogleSignInErrors.diagnosticCode(e)
        assertTrue(code.startsWith("NoCredentialException/"))
        assertFalse(code.contains("TOKEN-SECRETO"))
        assertFalse(code.contains("@"))
        assertFalse(GoogleSignInErrors.userText(GoogleSignInErrors.classify(e), e).contains("TOKEN-SECRETO"))
    }

    @Test fun `una excepcion que no es de credenciales solo da su nombre`() {
        assertEquals("IllegalStateException", GoogleSignInErrors.diagnosticCode(IllegalStateException("datos")))
    }

    @Test fun `la cancelacion no muestra codigo tecnico`() {
        val e = GetCredentialCancellationException("x")
        val text = GoogleSignInErrors.userText(GoogleSignInFailure.CANCELLED, e)
        assertEquals(GoogleSignInErrors.message(GoogleSignInFailure.CANCELLED), text)
    }

    @Test fun `un fallo real muestra mensaje y codigo`() {
        val e = NoCredentialException("x")
        val text = GoogleSignInErrors.userText(GoogleSignInFailure.NO_ACCOUNT, e)
        assertTrue(text.startsWith(GoogleSignInErrors.message(GoogleSignInFailure.NO_ACCOUNT)))
        assertTrue(text.endsWith("]"))
    }
}
