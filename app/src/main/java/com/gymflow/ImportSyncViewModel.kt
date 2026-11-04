package com.gymflow

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.launch

/** Estado de las pantallas Perfil → Importar datos y Perfil → Sincronización. Nunca se muestra éxito sin una verificación real. */
class ImportSyncViewModel(app: Application) : AndroidViewModel(app) {

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
        viewModelScope.launch {
            importUi = try { ImportUi.Preview(SyncService.planImport(getApplication(), uri)) } catch (e: Exception) { ImportUi.Error(friendly(e)) }
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
        viewModelScope.launch {
            importUi = try { ImportUi.Done(SyncService.applyImport(getApplication(), ui.plan, importChoices)) } catch (e: Exception) { ImportUi.Error(friendly(e)) }
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
        viewModelScope.launch {
            try {
                status = SyncService.status(getApplication())
                conflicts = if (status.configured && status.onboarded) SyncService.conflicts(getApplication()) else emptyList()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin sesión o sin servidor: estado vacío */ }
        }
    }

    fun startOnboarding() {
        copySavedOutside = false
        onboardUi = OnboardUi.Working("Comparando este móvil con el servidor… (solo lectura)")
        viewModelScope.launch {
            onboardUi = try { OnboardUi.Review(SyncService.prepareOnboarding(getApplication())) } catch (e: Exception) { OnboardUi.Error(friendly(e)) }
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
        viewModelScope.launch {
            onboardUi = try { OnboardUi.Done(SyncService.onboard(getApplication(), ui.plan, copySavedOutside)).also { refresh() } } catch (e: Exception) { OnboardUi.Error(friendly(e)) }
        }
    }

    fun closeOnboarding() { onboardUi = OnboardUi.Idle; copySavedOutside = false; refresh() }

    fun resolve(root: Key, keepLocal: Boolean) {
        viewModelScope.launch {
            try { SyncService.resolve(getApplication(), root, keepLocal); message = if (keepLocal) "Se conserva la versión de este móvil." else "Se usa la versión del servidor (la tuya queda archivada)." }
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
        is RemoteException -> SyncManager.describe(e.kind)
        is SyncService.NoSessionException -> "Inicia sesión para continuar."
        is IllegalArgumentException -> "No hay nada que aplicar con estas opciones."
        else -> "No se pudo completar la operación. No se ha modificado ningún dato."
    }
}
