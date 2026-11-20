package com.gymflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardas estáticas sobre el código de PRODUCCIÓN (no reimplementan nada): detectan regresiones que un
 * test unitario JVM no puede ejecutar porque dependen del runtime de Android.
 */
class GoogleSignInSourceGuardTest {

    private val dir = File("src/main/java/com/gymflow")
    private fun src(name: String) = File(dir, name).readText()
    private val signInFiles = listOf(
        "MainActivity.kt", "GoogleSignInErrors.kt", "GoogleSignInSupport.kt",
        "GoogleSignInRequestFactory.kt", "GoogleFirebaseSignIn.kt",
    )

    @Test fun `ningun fuente de produccion vuelve a la opcion de hoja inferior`() {
        val culpables = dir.walkTopDown().filter { it.extension == "kt" && it.readText().contains("GetGoogleIdOption") }
            .map { it.name }.toList()
        assertTrue("regresión a la opción automática en: $culpables", culpables.isEmpty())
    }

    @Test fun `la solicitud se construye solo en la factoria y con el boton de Google`() {
        val factoria = src("GoogleSignInRequestFactory.kt")
        assertTrue(factoria.contains("GetSignInWithGoogleOption.Builder(GoogleSignInConfig.WEB_CLIENT_ID)"))
        assertTrue(src("MainActivity.kt").contains("GoogleSignInRequestFactory.create()"))
        assertFalse("MainActivity no debe construir la solicitud por su cuenta",
            src("MainActivity.kt").contains("GetCredentialRequest.Builder"))
    }

    @Test fun `el flujo de login no usa mensajes ni causas de excepciones`() {
        val prohibido = Regex("""\.message\b|localizedMessage|printStackTrace|\.cause\b|\.toString\(\)\s*\)\s*$""")
        for (f in signInFiles) {
            val lineas = src(f).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            val malas = lineas.filter { prohibido.containsMatchIn(it) }
            assertTrue("$f usa datos de excepciones: $malas", malas.isEmpty())
        }
    }

    @Test fun `ninguna llamada a Log interpola excepciones ni errores`() {
        val interpola = Regex("""\$\{?(e|error|exception|task\.exception)\b""")
        for (f in signInFiles) {
            val malas = src(f).lines().filter { it.contains("Log.") && interpola.containsMatchIn(it) }
            assertTrue("$f registra una excepción: $malas", malas.isEmpty())
        }
    }

    @Test fun `el cierre del login en Firebase no referencia ninguna Activity`() {
        val codigo = src("GoogleFirebaseSignIn.kt").lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/**") }
            .joinToString("\n")
        assertFalse(codigo.contains("Activity"))
        assertFalse(codigo.contains("this@"))
    }

    @Test fun `la pantalla observa el estado real de la sesion y retira el listener`() {
        val s = src("MainActivity.kt")
        assertTrue(s.contains("addAuthStateListener"))
        assertTrue(s.contains("removeAuthStateListener"))
        assertTrue(s.contains("AuthNavigation.onAuthStateChanged"))
        assertFalse("el login de Google ya no navega por callback", s.contains("onGoogleSignIn { if"))
    }

    @Test fun `la cancelacion de corrutinas se relanza`() {
        assertTrue(src("MainActivity.kt").contains("catch (e: kotlinx.coroutines.CancellationException)"))
        assertTrue(src("GoogleSignInSupport.kt").contains("CancellationException"))
    }
}
