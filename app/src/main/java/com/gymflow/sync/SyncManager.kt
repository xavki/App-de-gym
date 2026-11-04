package com.gymflow.sync

import android.content.Context
import androidx.work.BackoffPolicy
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
import com.gymflow.synccore.Content
import com.gymflow.synccore.OperationIdentity
import com.gymflow.synccore.RemoteException
import com.gymflow.synccore.ServerUrl
import com.gymflow.synccore.SyncOutcome
import com.gymflow.synccore.SyncRunner
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Estado visible en la pantalla de perfil. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    /** Este dispositivo aún no se ha incorporado al servidor: no se hace NINGUNA llamada de red hasta confirmarlo. */
    data object NeedsOnboarding : SyncState
    data class Done(val at: Long, val pushed: Int, val pulled: Int, val openConflicts: Int) : SyncState
    data class Failed(val at: Long, val message: String, val retryable: Boolean) : SyncState
}

/**
 * Punto de entrada de la sincronización para el resto de la app (protocolo v2, motor de :syncCore).
 *  · Room es la fuente local; el servidor solo intercambia cambios.
 *  · Nada se sube ni se baja hasta que el usuario confirme la incorporación inicial de este dispositivo (SyncService).
 *  · WorkManager se encarga de la ejecución diferida con reintentos exponenciales.
 */
object SyncManager {

    private const val PREFS = "GymFlowSync"
    private const val WORK_NOW = "gymflow-sync"
    private const val WORK_PERIODIC = "gymflow-sync-periodic"

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** URL válida del servidor, o vacía (= sincronización desactivada). Una dirección no válida NUNCA se usa. */
    fun serverUrl(context: Context): String {
        val saved = prefs(context).getString("server_url", null) ?: BuildConfig.SYNC_URL
        return ServerUrl.validate(saved, allowInsecureLocal = BuildConfig.DEBUG).url.orEmpty()
    }

    /** Devuelve un mensaje de error en español si la dirección no es válida (y entonces no se guarda nada). */
    fun setServerUrl(context: Context, url: String): String? {
        val v = ServerUrl.validate(url, allowInsecureLocal = BuildConfig.DEBUG)
        if (!v.ok) return v.error
        prefs(context).edit().putString("server_url", v.url.orEmpty()).apply()
        _state.value = SyncState.Idle
        schedule(context)
        return null
    }

    fun isConfigured(context: Context) = serverUrl(context).isNotBlank()

    /** Identifica el servidor: cambiar de servidor obliga a una nueva incorporación inicial. */
    fun serverKey(context: Context): String = Content.sha256(serverUrl(context)).take(16)

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /** Identidad VIVA ahora mismo: cuenta de Firebase + servidor configurado (null si falta alguna). */
    fun liveIdentity(context: Context): String? {
        val uid = currentUid() ?: return null
        if (!isConfigured(context)) return null
        return OperationIdentity.of(uid, serverKey(context))
    }

    /**
     * Cliente del servidor ATADO a la cuenta [expectedUid] (la de la operación). Si la sesión cambia, ni se pide token ni se envía nada;
     * y si cambia mientras se pedía el token, el token de la cuenta nueva se descarta. Nunca viaja un token de otra cuenta.
     */
    fun api(context: Context, expectedUid: String): HttpSyncApi? {
        val url = serverUrl(context).takeIf { it.isNotBlank() } ?: return null
        if (currentUid() != expectedUid) return null
        return HttpSyncApi(url, token = { force ->
            val user = FirebaseAuth.getInstance().currentUser ?: throw RemoteException(RemoteException.Kind.AUTH, "Sin sesión")
            if (user.uid != expectedUid) throw RemoteException(RemoteException.Kind.IDENTITY, "La cuenta cambió")
            val t = user.getIdToken(force).await().token ?: throw RemoteException(RemoteException.Kind.AUTH, "Sin token de Firebase")
            if (FirebaseAuth.getInstance().currentUser?.uid != expectedUid) throw RemoteException(RemoteException.Kind.IDENTITY, "La cuenta cambió")
            t
        }, allowInsecureLocal = BuildConfig.DEBUG)
    }

    /** Almacén ligado a la cuenta y servidor de AHORA; su identidad viva se vuelve a consultar en cada paso. */
    fun store(context: Context): RoomSyncStore? {
        val uid = currentUid() ?: return null
        if (!isConfigured(context)) return null
        val appCtx = context.applicationContext
        return RoomSyncStore(GymDatabase.get(context), uid, serverKey(context)) { liveIdentity(appCtx) }
    }

    /** Sincroniza ahora (en la corrutina que llama). null = no hay servidor o sesión. */
    suspend fun syncNow(context: Context): SyncOutcome? {
        val store = store(context) ?: return null
        val api = api(context, currentUid() ?: return null) ?: return null
        _state.value = SyncState.Running
        val outcome = SyncRunner(store, api).run()
        _state.value = when (outcome) {
            SyncOutcome.OnboardingRequired -> SyncState.NeedsOnboarding
            is SyncOutcome.Success -> SyncState.Done(System.currentTimeMillis(), outcome.pushed, outcome.pulled, outcome.openConflicts)
            is SyncOutcome.Failed -> SyncState.Failed(System.currentTimeMillis(), describe(outcome.kind), outcome.retryable)
        }
        return outcome
    }

    fun describe(kind: RemoteException.Kind): String = when (kind) {
        RemoteException.Kind.NETWORK -> "Sin conexión con el servidor. Se reintentará."
        RemoteException.Kind.SERVER -> "El servidor no responde correctamente. Se reintentará."
        RemoteException.Kind.AUTH -> "La sesión ha caducado. Vuelve a iniciar sesión."
        RemoteException.Kind.IDENTITY -> "La cuenta o el servidor cambiaron durante la operación. No se aplicó nada."
        RemoteException.Kind.REJECTED -> "El servidor rechazó los datos. Revisa la versión de la app."
    }

    /** Pide una sincronización en segundo plano en cuanto haya red (se agrupan peticiones seguidas: espera de 5 s que se reinicia). */
    fun requestSync(context: Context) {
        if (!isConfigured(context)) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, work)
    }

    /** Sincronización periódica (cada 6 h). El trabajo comprueba por sí mismo la incorporación: sin ella no toca la red. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!isConfigured(context)) { wm.cancelUniqueWork(WORK_PERIODIC); return }
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        requestSync(context)
    }

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = SyncManager.syncNow(applicationContext)) {
        null, SyncOutcome.OnboardingRequired -> Result.success()                 // nada que hacer (y sin red)
        is SyncOutcome.Success -> Result.success()
        is SyncOutcome.Failed -> if (outcome.retryable) Result.retry() else Result.failure()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
