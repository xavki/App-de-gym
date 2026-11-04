package com.gymflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gymflow.sync.SyncState
import com.gymflow.synccore.ConflictGroup
import com.gymflow.synccore.ConflictReason
import com.gymflow.synccore.OAction
import com.gymflow.synccore.ServerUrl
import java.text.DateFormat
import java.util.Date

/** Perfil → estado de la sincronización con el servidor propio, incorporación inicial, conflictos y vinculación del panel web. */
@Composable
fun SyncSection(viewModel: GymFlowViewModel) {
    val sync: ImportSyncViewModel = viewModel()
    var editUrl by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var confirmResolve by remember { mutableStateOf<Pair<ConflictGroup, Boolean>?>(null) }
    val configured = viewModel.serverUrl.isNotBlank()
    val status = sync.status

    LaunchedEffect(viewModel.syncState, viewModel.serverUrl) { sync.refresh() }

    Text("SINCRONIZACIÓN", color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(12.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (configured) viewModel.serverUrl.removePrefix("https://") else "Sin servidor configurado", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(statusText(viewModel.syncState, viewModel.pendingChanges, configured, status.onboarded), color = TextSecondary, fontSize = 12.sp)
                }
                if (viewModel.syncState is SyncState.Running) CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            if (configured) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Última sincronización correcta: " + (status.lastSuccessAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "ninguna") +
                        " · Conflictos: ${status.openConflicts}" + (status.lastErrorKind?.let { " · Último error: ${errorLabel(it)}" } ?: ""),
                    color = TextSecondary, fontSize = 12.sp
                )
                if (!status.onboarded) {
                    Spacer(Modifier.height(6.dp))
                    Text("Este móvil aún no se ha unido al servidor. No se sube ni se baja nada hasta que revises y confirmes la incorporación.", color = AccentRed, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (configured && !status.onboarded) {
                    Button(onClick = { sync.startOnboarding() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BackgroundDark)) { Text("Revisar incorporación", fontWeight = FontWeight.Bold) }
                } else if (configured) {
                    val failed = viewModel.syncState is SyncState.Failed
                    Button(onClick = { viewModel.syncNow() }, enabled = viewModel.syncState !is SyncState.Running, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BackgroundDark)) { Text(if (failed) "Reintentar" else "Sincronizar", fontWeight = FontWeight.Bold) }
                }
                OutlinedButton(onClick = { editUrl = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Servidor")
                }
            }
            if (sync.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Conflictos por resolver (ninguna versión se pierde):", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                sync.conflicts.forEach { g ->
                    Spacer(Modifier.height(6.dp))
                    Text("${kindLabel(g.root.table)}: ${g.label}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(reasonLabel(g.reason) + if (!g.complete) " · descargando versiones del servidor, sincroniza de nuevo" else "", color = TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmResolve = g to true }, enabled = g.complete, shape = RoundedCornerShape(10.dp)) { Text("Conservar este móvil", fontSize = 12.sp) }
                        OutlinedButton(onClick = { confirmResolve = g to false }, enabled = g.complete, shape = RoundedCornerShape(10.dp)) { Text("Usar el servidor", fontSize = 12.sp) }
                    }
                }
            }
            sync.message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = AccentCyan, fontSize = 12.sp) }
            if (configured && status.onboarded) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.createPairingCode { pairingCode = it } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)) {
                    Icon(Icons.Default.Computer, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Vincular panel web")
                }
            }
        }
    }

    if (editUrl) ServerUrlDialog(
        current = viewModel.serverUrl,
        onSave = { url -> viewModel.updateServerUrl(url).also { if (it == null) editUrl = false } },
        onDismiss = { editUrl = false }
    )

    confirmResolve?.let { (g, keepLocal) ->
        AlertDialog(
            onDismissRequest = { confirmResolve = null }, containerColor = SurfaceDark,
            title = { Text(if (keepLocal) "Conservar este móvil" else "Usar el servidor", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = {
                Text(if (keepLocal) "La versión de este móvil pasará al servidor y a tus otros dispositivos. La del servidor queda archivada."
                else "La versión del servidor sustituirá a la de este móvil. La tuya queda archivada y recuperable.", color = TextSecondary)
            },
            confirmButton = { TextButton(onClick = { sync.resolve(g.root, keepLocal); confirmResolve = null }) { Text("Confirmar", color = AccentCyan) } },
            dismissButton = { TextButton(onClick = { confirmResolve = null }) { Text("Cancelar", color = TextSecondary) } }
        )
    }

    OnboardingDialog(sync)

    pairingCode?.let { code ->
        AlertDialog(
            onDismissRequest = { pairingCode = null }, containerColor = SurfaceDark,
            title = { Text("Vincular panel web", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Abre ${viewModel.serverUrl} en el ordenador y escribe este código:", color = TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Text(code, color = AccentCyan, fontSize = 32.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    Text("Caduca en 10 minutos y sirve una sola vez. El panel solo puede leer tus datos.", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { pairingCode = null }) { Text("Hecho", color = AccentCyan) } },
            dismissButton = { TextButton(onClick = { viewModel.revokePanels { pairingCode = null } }) { Text("Desvincular todos", color = AccentRed) } }
        )
    }
}

/** Incorporación inicial: vista previa SOLO de lectura, copia recuperable obligatoria y confirmación expresa. */
@Composable
private fun OnboardingDialog(sync: ImportSyncViewModel) {
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { sync.saveExternalCopy(it) } }
    when (val ui = sync.onboardUi) {
        ImportSyncViewModel.OnboardUi.Idle -> {}
        is ImportSyncViewModel.OnboardUi.Working -> AlertDialog(
            onDismissRequest = {}, containerColor = SurfaceDark,
            title = { Text("Incorporar este móvil", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(ui.text, color = TextSecondary) } },
            confirmButton = {}
        )
        is ImportSyncViewModel.OnboardUi.Review -> {
            val plan = ui.plan
            AlertDialog(
                onDismissRequest = { sync.closeOnboarding() }, containerColor = SurfaceDark,
                title = { Text("Incorporar este móvil", color = AccentWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Se ha comparado este móvil con el servidor SIN cambiar nada:", color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Se subirán (solo están aquí): ${plan.count(OAction.UPLOAD)}", color = TextPrimary)
                        Text("Se descargarán (solo están en el servidor): ${plan.count(OAction.DOWNLOAD)}", color = TextPrimary)
                        Text("Ya coinciden: ${plan.count(OAction.ADOPT)}", color = TextPrimary)
                        Text("Se unifican (mismo contenido, ids distintos): ${plan.count(OAction.HARMONIZE)}", color = TextPrimary)
                        Text("En conflicto: ${plan.count(OAction.CONFLICT)} (no se tocan; los resuelves tú después)", color = if (plan.count(OAction.CONFLICT) > 0) AccentRed else TextPrimary, fontWeight = FontWeight.Bold)
                        plan.warnings.forEach { Spacer(Modifier.height(6.dp)); Text("• $it", color = TextSecondary, fontSize = 12.sp) }
                        Spacer(Modifier.height(10.dp))
                        Text("Antes de unirse, la app crea y verifica una copia en su almacenamiento privado. Además, guarda una copia FUERA del móvil: si desinstalas la app, la privada se pierde.", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { saver.launch("gymflow-copia-${System.currentTimeMillis()}.json") }, shape = RoundedCornerShape(10.dp)) { Text("Guardar copia fuera del móvil", fontSize = 12.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sync.copySavedOutside, onCheckedChange = { sync.copySavedOutside = it })
                            Text("He guardado una copia fuera del móvil", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sync.confirmOnboarding() }, enabled = sync.copySavedOutside) { Text("Incorporar", color = AccentCyan) } },
                dismissButton = { TextButton(onClick = { sync.closeOnboarding() }) { Text("Cancelar", color = TextSecondary) } }
            )
        }
        is ImportSyncViewModel.OnboardUi.Done -> AlertDialog(
            onDismissRequest = { sync.closeOnboarding() }, containerColor = SurfaceDark,
            title = { Text("Móvil incorporado y verificado", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = {
                val r = ui.result
                Column {
                    Text("Pendientes de subir: ${r.uploaded} · Descargados: ${r.downloaded} · Coinciden: ${r.adopted} · Unificados: ${r.harmonizedRemoved} · Conflictos: ${r.conflicts}", color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("Comprobado dentro de la misma operación. A partir de ahora la sincronización es automática.", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { sync.closeOnboarding() }) { Text("Hecho", color = AccentCyan) } }
        )
        is ImportSyncViewModel.OnboardUi.Error -> AlertDialog(
            onDismissRequest = { sync.closeOnboarding() }, containerColor = SurfaceDark,
            title = { Text("No se incorporó este móvil", color = AccentRed, fontWeight = FontWeight.Bold) },
            text = { Text(ui.message + "\nNo se ha modificado ningún dato.", color = TextPrimary) },
            confirmButton = { TextButton(onClick = { sync.closeOnboarding() }) { Text("Cerrar", color = AccentCyan) } }
        )
    }
}

@Composable
private fun ServerUrlDialog(current: String, onSave: (String) -> String?, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(current.ifBlank { "https://" }) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val check = ServerUrl.validate(if (url == "https://") "" else url, allowInsecureLocal = BuildConfig.DEBUG)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = SurfaceDark,
        title = { Text("Servidor", color = AccentWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Dirección de tu servidor GymFlow (por ejemplo https://gym.tudominio.com). Déjalo vacío para no sincronizar. Al cambiar de servidor, este móvil deberá incorporarse de nuevo.", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim(); saveError = null }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = !check.ok || saveError != null,
                    supportingText = { (saveError ?: check.error)?.let { Text(it) } },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                )
            }
        },
        confirmButton = { TextButton(onClick = { saveError = onSave(if (url == "https://") "" else url) }, enabled = check.ok) { Text("Guardar", color = AccentCyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) } }
    )
}

private fun statusText(state: SyncState, pending: Int, configured: Boolean, onboarded: Boolean): String {
    if (!configured) return "Tus datos solo están en este móvil"
    val pendingText = if (pending > 0) " · $pending cambios pendientes" else ""
    val time = { at: Long -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) }
    if (!onboarded) return "Pendiente de incorporar$pendingText"
    return when (state) {
        SyncState.Idle -> "Pendiente de sincronizar$pendingText"
        SyncState.Running -> "Sincronizando…"
        SyncState.NeedsOnboarding -> "Pendiente de incorporar$pendingText"
        is SyncState.Done -> "Sincronizado a las ${time(state.at)}$pendingText"
        is SyncState.Failed -> "Error a las ${time(state.at)}: ${state.message}"
    }
}

private fun errorLabel(kind: String) = when (kind) {
    "NETWORK" -> "sin conexión"; "SERVER" -> "servidor"; "AUTH" -> "sesión"; "IDENTITY" -> "cuenta"; "REJECTED" -> "datos rechazados"; else -> kind
}

private fun kindLabel(table: String) = when (table) {
    "workouts" -> "Entrenamiento"; "routines" -> "Rutina"; "exercises" -> "Ejercicio"; "body_measurements" -> "Medida"; "schedules" -> "Programación"; "achievements" -> "Logro"; else -> table
}

private fun reasonLabel(r: ConflictReason) = when (r) {
    ConflictReason.BOTH_CHANGED -> "Cambiado en este móvil y en otro"
    ConflictReason.DELETED_REMOTELY -> "Borrado en otro dispositivo, pero lo editaste aquí"
    ConflictReason.DELETED_LOCALLY -> "Borrado aquí, pero existe en el servidor"
    ConflictReason.FIRST_SYNC -> "Distinto en este móvil y en el servidor"
}
