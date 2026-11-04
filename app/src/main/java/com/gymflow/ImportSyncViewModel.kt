package com.gymflow

import android.app.Application
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gymflow.sync.SyncManager
import com.gymflow.sync.SyncService
import com.gymflow.sync.SyncStatus
import com.gymflow.synccore.ConflictGroup
import com.gymflow.synccore.ConflictResolver
import com.gymflow.synccore.IdentityChangedException
import com.gymflow.synccore.ImportChoices
import com.gymflow.synccore.ImportFileException
import com.gymflow.synccore.ImportPlan
import com.gymflow.synccore.ImportResult
import com.gymflow.synccore.ImportIntegrityException
import com.gymflow.synccore.Key
import com.gymflow.synccore.OnboardingBlockedException
import com.gymflow.synccore.OnboardingPlan
import com.gymflow.synccore.OnboardingResult
import com.gymflow.synccore.RemoteException
import com.gymflow.synccore.StaleImportPlanException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Estado de las pantallas Perfil → Importar datos y Perfil → Sincronización. Nunca se muestra éxito sin una verificación real. */
class ImportSyncViewModel(app: Application) : AndroidViewModel(app) {

    // H1/N-03/N-08: si cambia la cuenta, toda operación pendiente se invalida: se cancelan las corrutinas, se sube la «generación»
    // (los resultados tardíos de la cuenta anterior se ignoran) y se descartan las vistas previas. El listener se retira en onCleared.
    private var generation = 0
    private var ops = mutableListOf<Job>()
    private var firstAuthEvent = true
    private val authListener = FirebaseAuth.AuthStateListener {
        if (firstAuthEvent) { firstAuthEvent = false; return@AuthStateListener }
        generation++; ops.forEach { it.cancel() }; ops.clear()
        importUi = ImportUi.Idle; importChoices = ImportChoices(); onboardUi = OnboardUi.Idle; copySavedOutside = false
        status = SyncStatus(false, false, null, null, 0); conflicts = emptyList(); pendingLocalEditsFor = null; conflictsIdentity = null
        message = "La cuenta cambió: se descartó la operación pendiente. No se modificó nada."
    }
    init { FirebaseAuth.getInstance().addAuthStateListener(authListener) }
    override fun onCleared() { FirebaseAuth.getInstance().removeAuthStateListener(authListener); ops.forEach { it.cancel() }; super.onCleared() }

    /** Lanza una operación ligada a la generación actual: si la cuenta cambia mientras corre, su resultado no se publica. */
    private fun launchOp(body: suspend (isCurrent: () -> Boolean) -> Unit) {
        val gen = generation
        ops.removeAll { !it.isActive }
        ops += viewModelScope.launch { body { gen == generation } }
    }

    // ───────────── importación ─────────────
    sealed interface ImportUi {
        data object Idle : ImportUi
        data class Working(val text: String) : ImportUi
        data class Preview(val plan: ImportPlan) : ImportUi
        data class Done(val result: ImportResult) : ImportUi
        data class Error(val message: String) : ImportUi
    }

    var importUi by mutableStateOf<ImportUi>(ImportUi.Idle)
        private set
    var importChoices by mutableStateOf(ImportChoices())
        private set

    fun pickImportFile(uri: Uri) {
        importUi = ImportUi.Working("Analizando el archivo…")
        importChoices = ImportChoices()
        launchOp { current ->
            val r = try { ImportUi.Preview(SyncService.planImport(getApplication(), uri)) } catch (e: CancellationException) { throw e } catch (e: Exception) { ImportUi.Error(friendly(e)) }
            if (current()) importUi = r
        }
    }

    fun setAllowForeign(allow: Boolean) { importChoices = importChoices.copy(allowForeignAccount = allow) }

    fun toggleCopy(key: Key) {
        val s = importChoices.importAsCopy
        importChoices = importChoices.copy(importAsCopy = if (key in s) s - key else s + key)
    }

    fun confirmImport() {
        val ui = importUi as? ImportUi.Preview ?: return
        if (!ui.plan.canApply(importChoices)) return
        importUi = ImportUi.Working("Importando…")
        launchOp { current ->
            val r = try { ImportUi.Done(SyncService.applyImport(getApplication(), ui.plan, importChoices)) } catch (e: CancellationException) { throw e } catch (e: Exception) { ImportUi.Error(friendly(e)) }
            if (current()) importUi = r
        }
    }

    fun closeImport() { importUi = ImportUi.Idle; importChoices = ImportChoices() }

    // ───────────── sincronización ─────────────
    var status by mutableStateOf(SyncStatus(false, false, null, null, 0))
        private set
    var conflicts by mutableStateOf<List<ConflictGroup>>(emptyList())
        private set

    sealed interface OnboardUi {
        data object Idle : OnboardUi
        data class Working(val text: String) : OnboardUi
        data class Review(val plan: OnboardingPlan) : OnboardUi
        data class Done(val result: OnboardingResult) : OnboardUi
        data class Error(val message: String) : OnboardUi
    }

    var onboardUi by mutableStateOf<OnboardUi>(OnboardUi.Idle)
        private set
    var copySavedOutside by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    fun refresh() {
        launchOp { current ->
            try {
                val identity = SyncService.conflictsIdentity(getApplication())
                val st = SyncService.status(getApplication())
                val list = if (st.configured && st.onboarded) SyncService.conflicts(getApplication()) else emptyList()
                if (!current()) return@launchOp
                status = st; conflicts = list; conflictsIdentity = identity
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin sesión o sin servidor: estado vacío */ }
        }
    }

    fun startOnboarding() {
        copySavedOutside = false
        onboardUi = OnboardUi.Working("Comparando este móvil con el servidor… (solo lectura)")
        launchOp { current ->
            val r = try { OnboardUi.Review(SyncService.prepareOnboarding(getApplication())) } catch (e: CancellationException) { throw e } catch (e: Exception) { OnboardUi.Error(friendly(e)) }
            if (current()) onboardUi = r
        }
    }

    fun saveExternalCopy(target: Uri) {
        viewModelScope.launch {
            try { SyncService.saveRawBackup(getApplication(), target); message = "Copia guardada." } catch (e: Exception) { message = "No se pudo guardar la copia: ${friendly(e)}" }
        }
    }

    fun confirmOnboarding() {
        val ui = onboardUi as? OnboardUi.Review ?: return
        onboardUi = OnboardUi.Working("Creando y verificando la copia recuperable…")
        launchOp { current ->
            val r = try { OnboardUi.Done(SyncService.onboard(getApplication(), ui.plan, copySavedOutside)) } catch (e: CancellationException) { throw e } catch (e: Exception) { OnboardUi.Error(friendly(e)) }
            if (current()) { onboardUi = r; refresh() }
        }
    }

    fun closeOnboarding() { onboardUi = OnboardUi.Idle; copySavedOutside = false; refresh() }

    /** Conflicto cuya resolución «usar servidor» espera una segunda confirmación porque se editó localmente después (H3). */
    var pendingLocalEditsFor by mutableStateOf<Key?>(null)
    /** Identidad (cuenta+servidor) con la que se cargó la lista de conflictos mostrada; la resolución debe coincidir (C4). */
    private var conflictsIdentity: String? = null

    fun resolve(root: Key, keepLocal: Boolean, acknowledgeLocalEdits: Boolean = false) {
        val identity = conflictsIdentity
        launchOp { current ->
            try { SyncService.resolve(getApplication(), root, keepLocal, acknowledgeLocalEdits, identity); pendingLocalEditsFor = null; message = if (keepLocal) "Se conserva la versión de este móvil." else "Se usa la versión del servidor (la tuya queda archivada)." }
            catch (e: ConflictResolver.LocalChangedSinceConflictException) { pendingLocalEditsFor = root; message = e.message }
            catch (e: ConflictResolver.NotResolvableException) { message = e.message }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = friendly(e) }
            refresh()
        }
    }

    /** Mensajes comprensibles y seguros: nunca incluyen contenido del archivo, tokens ni identificadores. */
    private fun friendly(e: Throwable): String = when (e) {
        is ImportFileException -> e.message ?: "El archivo no es válido."
        is StaleImportPlanException -> e.message ?: "Los datos cambiaron: vuelve a analizar."
        is ImportIntegrityException -> "La verificación posterior falló y no se guardó nada."
        is OnboardingBlockedException -> e.message ?: "Incorporación bloqueada."
        is IdentityChangedException -> "La cuenta o el servidor cambiaron durante la operación. No se aplicó nada: vuelve a empezar."
        is RemoteException -> SyncManager.describe(e.kind)
        is SyncService.NoSessionException -> "Inicia sesión para continuar."
        is IllegalArgumentException -> "No hay nada que aplicar con estas opciones."
        else -> "No se pudo completar la operación. No se ha modificado ningún dato."
    }
}
