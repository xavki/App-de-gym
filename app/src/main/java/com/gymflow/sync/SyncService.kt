package com.gymflow.sync

import android.content.Context
import android.net.Uri
import com.gymflow.data.GymDatabase
import com.gymflow.synccore.BackupFormat
import com.gymflow.synccore.BackupProof
import com.gymflow.synccore.ConflictGroup
import com.gymflow.synccore.ConflictResolver
import com.gymflow.synccore.ImportChoices
import com.gymflow.synccore.ImportError
import com.gymflow.synccore.ImportExecutor
import com.gymflow.synccore.ImportFileException
import com.gymflow.synccore.ImportLimits
import com.gymflow.synccore.ImportPlan
import com.gymflow.synccore.ImportPlanner
import com.gymflow.synccore.ImportResult
import com.gymflow.synccore.ExportParser
import com.gymflow.synccore.Key
import com.gymflow.synccore.Onboarding
import com.gymflow.synccore.OnboardingBlockedException
import com.gymflow.synccore.OnboardingPlan
import com.gymflow.synccore.OnboardingResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Instantánea del estado de sincronización para la pantalla de perfil. */
data class SyncStatus(val configured: Boolean, val onboarded: Boolean, val lastSuccessAt: Long?, val lastErrorKind: String?, val openConflicts: Int)

/** Orquesta importación JSON, incorporación inicial y resolución de conflictos sobre Room. Toda la lógica de decisión vive en :syncCore. */
object SyncService {

    class NoSessionException : Exception("Inicia sesión para continuar.")

    private fun uid(): String = SyncManager.currentUid() ?: throw NoSessionException()

    // ───────────── importación JSON ─────────────

    private fun importStore(ctx: Context): RoomImportStore {
        val app = ctx.applicationContext
        return RoomImportStore(GymDatabase.get(ctx), uid(), SyncManager.serverKey(ctx)) { SyncManager.liveIdentity(app) }
    }

    /** Lee el archivo (con tope de tamaño), lo valida y compara con la base actual. NO escribe nada. */
    suspend fun planImport(ctx: Context, uri: Uri): ImportPlan {
        val uid = uid()
        val bytes = withContext(Dispatchers.IO) { readBounded(ctx, uri, ImportLimits().maxBytes) }
        val parsed = ExportParser.parse(bytes, uid)
        val store = importStore(ctx)                       // identidad = (cuenta, servidor) de ESTE momento; el plan la lleva consigo (H1)
        val local = store.transaction { all() }
        return ImportPlanner.plan(parsed, local, uid, ownerIdentity = store.boundIdentity)
    }

    /** Aplica en UNA transacción (todo o nada); falla con StaleImportPlanException si la base cambió desde la vista previa. */
    suspend fun applyImport(ctx: Context, plan: ImportPlan, choices: ImportChoices): ImportResult =
        ImportExecutor.apply(importStore(ctx), plan, choices).also { SyncManager.requestSync(ctx) }

    private fun readBounded(ctx: Context, uri: Uri, max: Int): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw ImportFileException(ImportError.NOT_JSON, "No se pudo abrir el archivo")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw ImportFileException(ImportError.TOO_LARGE, "El archivo supera ${max / 1_000_000} MB")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // ───────────── incorporación inicial ─────────────

    private fun syncStore(ctx: Context): RoomSyncStore = SyncManager.store(ctx) ?: throw IllegalStateException("Configura el servidor e inicia sesión")

    /** SOLO LEE (local y servidor) y prepara la vista previa. No cambia nada, no sube nada. */
    suspend fun prepareOnboarding(ctx: Context): OnboardingPlan {
        val store = syncStore(ctx)
        val uid = SyncManager.currentUid() ?: throw NoSessionException()
        val api = SyncManager.api(ctx, uid) ?: throw IllegalStateException("Configura el servidor e inicia sesión")
        return Onboarding.prepare(store, api)
    }

    /**
     * Copia recuperable de TODA la base local, escrita en almacenamiento privado, releída y comparada fila a fila.
     * null si no se pudo verificar (entonces la incorporación se bloquea).
     */
    suspend fun makeVerifiedBackup(ctx: Context): BackupProof? {
        val store = syncStore(ctx)
        val local = store.transaction { records() }
        val bytes = BackupFormat.serialize(local)
        return withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
            val tmp = File(dir, "pre-sync.tmp")
            val dest = File(dir, "pre-sync-${System.currentTimeMillis()}.json")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(dest)) return@withContext null
            BackupFormat.prove(dest.readBytes(), local)
        }
    }

    /** Copia cruda de la base local en el destino que elija el usuario (fuera del móvil). */
    suspend fun saveRawBackup(ctx: Context, target: Uri) {
        val bytes = BackupFormat.serialize(syncStore(ctx).transaction { records() })
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("No se pudo abrir el destino")
        }
    }

    /** Aplica la incorporación solo con copia recuperable verificada y confirmación expresa de la copia externa. */
    suspend fun onboard(ctx: Context, plan: OnboardingPlan, copySavedOutsidePhone: Boolean): OnboardingResult {
        if (!copySavedOutsidePhone) throw OnboardingBlockedException("Confirma que has guardado una copia fuera del móvil.")
        val result = Onboarding.apply(syncStore(ctx), plan, makeVerifiedBackup(ctx))
        SyncManager.schedule(ctx)
        return result
    }

    // ───────────── estado y conflictos ─────────────

    suspend fun status(ctx: Context): SyncStatus {
        if (!SyncManager.isConfigured(ctx) || SyncManager.currentUid() == null) return SyncStatus(false, false, null, null, 0)
        val store = syncStore(ctx)
        return store.transaction {
            val m = meta()
            SyncStatus(true, m.onboarded, m.lastSuccessAt, m.lastErrorKind, conflicts().size)
        }
    }

    suspend fun conflicts(ctx: Context): List<ConflictGroup> = syncStore(ctx).transaction { conflicts() }

    /** [acknowledgeLocalEdits]: el usuario confirmó de nuevo sabiendo que sus ediciones posteriores al conflicto se archivan (H3). */
    suspend fun resolve(ctx: Context, root: Key, keepLocal: Boolean, acknowledgeLocalEdits: Boolean = false) {
        val store = syncStore(ctx)
        if (keepLocal) ConflictResolver.keepLocal(store, root, System.currentTimeMillis())
        else ConflictResolver.useRemote(store, root, System.currentTimeMillis(), acknowledgeLocalEdits)
        SyncManager.requestSync(ctx)
    }
}
