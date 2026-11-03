package com.gymflow

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuración, validación de credenciales, errores de Firebase y política de sesión.
 * Todo en JVM: no hace falta móvil, emulador, Firebase ni red.
 */
class GoogleSignInSupportTest {

    private val tipoEsperado = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"

    // ---- Configuración de la solicitud de Google ----

    @Test fun `el cliente web configurado existe en google-services json para com gymflow`() {
        val root = Json.parseToJsonElement(File("google-services.json").readText()).jsonObject
        val cliente = root["client"]!!.jsonArray.map { it.jsonObject }.first {
            it["client_info"]!!.jsonObject["android_client_info"]!!.jsonObject["package_name"]!!.jsonPrimitive.content == "com.gymflow"
        }
        val oauth = cliente["oauth_client"]!!.jsonArray.map { it.jsonObject }
        val webIds = oauth.filter { it["client_type"]!!.jsonPrimitive.int == 3 }
            .map { it["client_id"]!!.jsonPrimitive.content }
        assertTrue("el WEB_CLIENT_ID no está entre los clientes web: $webIds", GoogleSignInConfig.WEB_CLIENT_ID in webIds)
        assertTrue("falta un cliente Android (tipo 1)", oauth.any { it["client_type"]!!.jsonPrimitive.int == 1 })
    }

    @Test fun `el cliente web tiene formato de ID de cliente de Google`() {
        assertTrue(Regex("^\\d+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$").matches(GoogleSignInConfig.WEB_CLIENT_ID))
    }

    // ---- Validación del tipo de credencial ----

    @Test fun `solo se acepta el tipo de credencial Google ID token`() {
        assertTrue(GoogleCredentialCheck.isExpectedType(tipoEsperado, tipoEsperado))
        assertFalse(GoogleCredentialCheck.isExpectedType("androidx.credentials.TYPE_PASSWORD_CREDENTIAL", tipoEsperado))
        assertFalse(GoogleCredentialCheck.isExpectedType("", tipoEsperado))
        assertFalse(GoogleCredentialCheck.isExpectedType(null, tipoEsperado))
    }

    // ---- Credenciales mal formadas ----

    @Test fun `un token con forma de JWT es utilizable`() {
        assertTrue(GoogleCredentialCheck.isUsableToken("aaa.bbb.ccc"))
    }

    @Test fun `tokens vacios, nulos o mal formados no son utilizables`() {
        for (malo in listOf(null, "", "   ", "abc", "a.b", "a.b.c.d", "a..c", ".b.c", "a.b.")) {
            assertFalse("debería rechazar: $malo", GoogleCredentialCheck.isUsableToken(malo))
        }
    }

    // ---- Errores de Firebase ----

    @Test fun `clasificacion de errores de Firebase por clase y codigo`() {
        fun c(clase: String, codigo: String? = null) = FirebaseSignInErrors.classify(FirebaseErrorInfo(clase, codigo))
        assertEquals(FirebaseSignInFailure.NETWORK, c("com.google.firebase.FirebaseNetworkException"))
        assertEquals(FirebaseSignInFailure.TOO_MANY_REQUESTS, c("com.google.firebase.FirebaseTooManyRequestsException"))
        assertEquals(FirebaseSignInFailure.INVALID_CREDENTIAL, c("com.google.firebase.auth.FirebaseAuthInvalidCredentialsException", "ERROR_INVALID_CREDENTIAL"))
        assertEquals(FirebaseSignInFailure.ACCOUNT_DISABLED, c("com.google.firebase.auth.FirebaseAuthInvalidUserException", "ERROR_USER_DISABLED"))
        assertEquals(FirebaseSignInFailure.ACCOUNT_CONFLICT, c("com.google.firebase.auth.FirebaseAuthUserCollisionException", "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"))
        assertEquals(FirebaseSignInFailure.TOO_MANY_REQUESTS, c("com.google.firebase.auth.FirebaseAuthException", "ERROR_TOO_MANY_REQUESTS"))
        assertEquals(FirebaseSignInFailure.UNKNOWN, c("java.lang.RuntimeException"))
        assertEquals(FirebaseSignInFailure.UNKNOWN, c("com.google.firebase.auth.FirebaseAuthException", "ERROR_ALGO_NUEVO"))
        assertEquals(FirebaseSignInFailure.UNKNOWN, FirebaseSignInErrors.classify(null))
    }

    @Test fun `los codigos de Firebase son fijos, unicos y con formato permitido`() {
        val codes = FirebaseSignInFailure.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        for (c in codes) assertTrue("código no permitido: $c", Regex("^F-[A-Z]+$").matches(c))
    }

    @Test fun `los mensajes de Firebase estan en espanol y no filtran datos`() {
        for (f in FirebaseSignInFailure.values()) {
            val texto = FirebaseSignInErrors.userText(f)
            assertTrue(FirebaseSignInErrors.message(f).isNotBlank())
            assertTrue(texto.endsWith("[${f.code}]"))
            for (prohibido in listOf("@", "eyJ", "Exception", "uid", "token", "firebase.google")) {
                assertFalse("$prohibido en: $texto", texto.contains(prohibido, ignoreCase = true))
            }
        }
    }

    @Test fun `FirebaseErrorInfo no transporta el mensaje de la excepcion`() {
        // Si alguien añadiera un campo con el mensaje, este test obliga a revisar la privacidad.
        // Solo cuentan los campos de INSTANCIA (los datos que transporta el objeto): el compilador, p. ej. el
        // plugin de Compose, puede añadir campos ESTÁTICOS que no guardan datos de ningún objeto.
        val campos = FirebaseErrorInfo::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }.sorted()
        assertEquals("campos de instancia: $campos", listOf("className", "errorCode"), campos)
    }

    // ---- Persistencia / recuperación de sesión ----

    @Test fun `sin sesion no se cierra nada al arrancar`() {
        assertFalse(SessionPolicy.shouldSignOutAtStart(hasUser = false, rememberMe = false))
        assertFalse(SessionPolicy.shouldSignOutAtStart(hasUser = false, rememberMe = true))
    }

    @Test fun `con sesion y recordarme la sesion se recupera`() {
        assertFalse(SessionPolicy.shouldSignOutAtStart(hasUser = true, rememberMe = true))
    }

    @Test fun `con sesion pero sin recordarme se cierra al arrancar`() {
        assertTrue(SessionPolicy.shouldSignOutAtStart(hasUser = true, rememberMe = false))
    }

    @Test fun `la configuracion no es nula`() {
        assertNotNull(GoogleSignInConfig.WEB_CLIENT_ID)
    }

    // ---- resolveToken: la misma función que usa la app (credenciales incorrectas o mal formadas) ----

    private fun resolver(custom: Boolean = true, tipo: String? = tipoEsperado, parse: () -> String?) =
        GoogleCredentialCheck.resolveToken(custom, tipo, tipoEsperado, parse)

    @Test fun `credencial correcta devuelve el token`() {
        assertEquals(TokenResult.Ok("aaa.bbb.ccc"), resolver { "aaa.bbb.ccc" })
    }

    @Test fun `credencial que no es personalizada se rechaza sin intentar leerla`() {
        var leida = false
        val r = resolver(custom = false) { leida = true; "aaa.bbb.ccc" }
        assertEquals(TokenResult.Failed(GoogleSignInFailure.INVALID_RESPONSE), r)
        assertFalse("no debe parsear una credencial de tipo incorrecto", leida)
    }

    @Test fun `tipo de credencial incorrecto o ausente se rechaza`() {
        for (tipo in listOf("androidx.credentials.TYPE_PASSWORD_CREDENTIAL", "", null)) {
            var leida = false
            val r = resolver(tipo = tipo) { leida = true; "aaa.bbb.ccc" }
            assertEquals(TokenResult.Failed(GoogleSignInFailure.INVALID_RESPONSE), r)
            assertFalse(leida)
        }
    }

    @Test fun `credencial mal formada que lanza al leerla es respuesta no valida`() {
        assertEquals(TokenResult.Failed(GoogleSignInFailure.INVALID_RESPONSE), resolver { throw IllegalArgumentException("x") })
    }

    @Test fun `token nulo, vacio o sin forma de JWT es respuesta no valida`() {
        for (malo in listOf(null, "", "  ", "abc", "a.b", "a.b.c.d", "a..c")) {
            assertEquals("para $malo", TokenResult.Failed(GoogleSignInFailure.INVALID_RESPONSE), resolver { malo })
        }
    }

    @Test fun `la cancelacion de la corrutina se propaga y no se convierte en error`() {
        try {
            resolver { throw java.util.concurrent.CancellationException("cancelada") }
            org.junit.Assert.fail("debía propagar la cancelación")
        } catch (e: java.util.concurrent.CancellationException) {
            // correcto
        }
    }

    // ---- Recreación de Activity y recuperación de sesión (navegación por estado real) ----

    @Test fun `al arrancar con sesion se abre inicio y sin sesion el login`() {
        assertEquals(Screen.HOME, AuthNavigation.initialScreen(hasUser = true))
        assertEquals(Screen.LOGIN, AuthNavigation.initialScreen(hasUser = false))
    }

    @Test fun `Firebase completa despues de recrearse la Activity y se sale del login`() {
        // Activity nueva: arranca en LOGIN (aún sin sesión) y luego Firebase notifica la sesión.
        var pantalla = AuthNavigation.initialScreen(hasUser = false)
        pantalla = AuthNavigation.onAuthStateChanged(pantalla, hasUser = true)
        assertEquals(Screen.HOME, pantalla)
    }

    @Test fun `notificaciones repetidas de sesion no duplican la navegacion`() {
        val una = AuthNavigation.onAuthStateChanged(Screen.LOGIN, hasUser = true)
        val dos = AuthNavigation.onAuthStateChanged(una, hasUser = true)
        assertEquals(Screen.HOME, una)
        assertEquals(una, dos)
    }

    @Test fun `un cambio de sesion no arranca al usuario de otras pantallas`() {
        for (s in Screen.values().filter { it != Screen.LOGIN }) {
            assertEquals(s, AuthNavigation.onAuthStateChanged(s, hasUser = true))
            assertEquals(s, AuthNavigation.onAuthStateChanged(s, hasUser = false))
        }
    }

    @Test fun `sin sesion se permanece en el login`() {
        assertEquals(Screen.LOGIN, AuthNavigation.onAuthStateChanged(Screen.LOGIN, hasUser = false))
    }

    // ---- Logs sin datos privados (misma función que usa la app) ----

    @Test fun `las lineas de log solo contienen el codigo fijo`() {
        for (f in GoogleSignInFailure.values()) {
            assertEquals("Google Sign-In: ${f.code}", GoogleSignInErrors.logMessage(f))
        }
        for (f in FirebaseSignInFailure.values()) {
            assertEquals("Firebase Auth: ${f.code}", FirebaseSignInErrors.logMessage(f))
        }
    }
}
