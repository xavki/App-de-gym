package com.gymflow.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.gymflow.BuildConfig
import com.gymflow.data.GymDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Estado visible en la pantalla de perfil. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val at: Long, val result: SyncResult) : SyncState
    data class Failed(val at: Long, val message: String) : SyncState
}

/**
 * Punto de entrada de la sincronización para el resto de la app: configuración
 * (URL del servidor), ejecución inmediata y programación en segundo plano.
 */
object SyncManager {

    private const val PREFS = "GymFlowSync"
    private const val WORK_NOW = "gymflow-sync"
    private const val WORK_PERIODIC = "gymflow-sync-periodic"

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** URL del servidor: la guardada en la app o, si no hay, la de local.properties. Vacía = desactivado. */
    fun serverUrl(context: Context): String =
        prefs(context).getString("server_url", null) ?: BuildConfig.SYNC_URL

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString("server_url", url.trim().trimEnd('/')).apply()
        schedule(context)
    }

    fun isConfigured(context: Context) = serverUrl(context).isNotBlank()

    fun lastSync(context: Context, uid: String): Long = prefs(context).getLong("last_sync_$uid", 0)

    /** Crea el cliente para el usuario con sesión de Firebase, o null si no hay sesión/servidor. */
    fun api(context: Context): SyncApi? {
        val url = serverUrl(context).takeIf { it.isNotBlank() } ?: return null
        FirebaseAuth.getInstance().currentUser ?: return null
        return HttpSyncApi(url, token = { force ->
            val user = FirebaseAuth.getInstance().currentUser ?: throw IOException("Sin sesión")
            user.getIdToken(force).await().token ?: throw IOException("Sin token de Firebase")
        })
    }

    private fun cursorStore(context: Context) = object : CursorStore {
        override fun get(uid: String) = prefs(context).getLong("cursor_$uid", 0)
        override fun set(uid: String, cursor: Long) { prefs(context).edit().putLong("cursor_$uid", cursor).apply() }
    }

    /** Sincroniza ahora (en la corrutina que llama). Devuelve null si no hay servidor o sesión. */
    suspend fun syncNow(context: Context): SyncResult? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val api = api(context) ?: return null
        _state.value = SyncState.Running
        return try {
            SyncEngine(GymDatabase.get(context), api, cursorStore(context)).sync(uid).also {
                val now = System.currentTimeMillis()
                prefs(context).edit().putLong("last_sync_$uid", now).apply()
                _state.value = SyncState.Done(now, it)
            }
        } catch (e: Exception) {
            _state.value = SyncState.Failed(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * Pide una sincronización en segundo plano en cuanto haya red. Varias peticiones
     * seguidas se agrupan en una (espera de 5 s que se reinicia).
     */
    fun requestSync(context: Context) {
        if (!isConfigured(context)) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, work)
    }

    /** Sincronización periódica (cada 6 h) para traer cambios de otros dispositivos. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!isConfigured(context)) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(networkConstraint).build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        requestSync(context)
    }

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SyncManager.syncNow(applicationContext)
        Result.success()
    } catch (e: SyncHttpException) {
        // 4xx (salvo 408/429) no se arregla reintentando; 5xx sí
        if (e.status in 500..599 || e.status == 408 || e.status == 429) Result.retry() else Result.failure()
    } catch (e: IOException) {
        Result.retry()
    } catch (e: Exception) {
        // p. ej. Firebase sin conexión al renovar el token
        Result.retry()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
