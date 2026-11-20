package com.gymflow

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Cierra el login de Google en Firebase SIN referencias a ninguna Activity: solo el contexto de la
 * aplicación y el FirebaseAuth global. Si la Activity se recrea mientras Firebase responde, nada queda
 * colgado; la pantalla reacciona al estado real de la sesión (AuthNavigation + AuthStateListener).
 */
object GoogleFirebaseSignIn {
    private const val TAG = "GymFlowAuth"

    fun start(auth: FirebaseAuth, appContext: Context, idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Login con Firebase exitoso")
                appContext.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("remember_me", true).apply()
                auth.currentUser?.let { user ->
                    FirebaseService.saveUser(user.uid, user.displayName ?: "Usuario Google", user.email ?: "")
                }
            } else {
                val error = task.exception
                val failure = FirebaseSignInErrors.classify(
                    error?.let { FirebaseErrorInfo(it.javaClass.name, (it as? FirebaseAuthException)?.errorCode) }
                )
                Log.w(TAG, FirebaseSignInErrors.logMessage(failure))
                Toast.makeText(appContext, FirebaseSignInErrors.userText(failure), Toast.LENGTH_LONG).show()
            }
        }
    }
}
