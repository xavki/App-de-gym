package com.gymflow

import androidx.credentials.GetCredentialRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Construcción de la solicitud de Google. Necesita el runtime de Android (usa Bundle internamente),
 * por eso es un test instrumentado: se ejecuta en emulador o móvil, NO en la JVM de CI.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInRequestTest {

    @Test fun solicitudUsaElBotonDeGoogleConElClienteWebConfigurado() {
        val opcion = GetSignInWithGoogleOption.Builder(GoogleSignInConfig.WEB_CLIENT_ID).build()
        val solicitud = GetCredentialRequest.Builder().addCredentialOption(opcion).build()

        assertEquals(1, solicitud.credentialOptions.size)
        val primera = solicitud.credentialOptions[0]
        assertTrue(primera is GetSignInWithGoogleOption)
        assertEquals(GoogleSignInConfig.WEB_CLIENT_ID, (primera as GetSignInWithGoogleOption).serverClientId)
    }
}
