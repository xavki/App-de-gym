package com.gymflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Solicitud de Google construida con la MISMA factoría que usa la app (GoogleSignInRequestFactory).
 * Necesita el runtime de Android (Bundle), por eso es instrumentado: emulador o móvil, NO la JVM de CI.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInRequestTest {

    @Test fun solicitudDeProduccionUsaElBotonDeGoogleConElClienteWeb() {
        val solicitud = GoogleSignInRequestFactory.create()

        assertEquals(1, solicitud.credentialOptions.size)
        val opcion = solicitud.credentialOptions[0]
        assertTrue(opcion is GetSignInWithGoogleOption)
        assertEquals(GoogleSignInConfig.WEB_CLIENT_ID, (opcion as GetSignInWithGoogleOption).serverClientId)
    }

    @Test fun solicitudDeProduccionNoUsaLaOpcionDeHojaInferior() {
        val solicitud = GoogleSignInRequestFactory.create()
        assertFalse(solicitud.credentialOptions.any { it is GetGoogleIdOption })
    }
}
