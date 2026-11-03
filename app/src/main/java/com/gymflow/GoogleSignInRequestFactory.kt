package com.gymflow

import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption

/**
 * Construye la solicitud de «Entrar con Google». La usan la app y el test instrumentado, de modo que
 * no hay dos implementaciones. Botón explícito → opción «Sign in with Google» (no la de la hoja inferior
 * automática, que puede no mostrarse según los ajustes de la cuenta de Google).
 */
object GoogleSignInRequestFactory {
    fun create(): GetCredentialRequest =
        GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(GoogleSignInConfig.WEB_CLIENT_ID).build())
            .build()
}
