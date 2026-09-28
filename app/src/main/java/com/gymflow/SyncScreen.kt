package com.gymflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymflow.sync.SyncState
import java.text.DateFormat
import java.util.Date

/** Perfil → estado de la sincronización con el servidor propio y vinculación del panel web. */
@Composable
fun SyncSection(viewModel: GymFlowViewModel) {
    var editUrl by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    val configured = viewModel.serverUrl.isNotBlank()

    Text("SINCRONIZACIÓN", color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(12.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (configured) viewModel.serverUrl.removePrefix("https://") else "Sin servidor configurado",
                        color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                    Text(statusText(viewModel.syncState, viewModel.pendingChanges, configured), color = TextSecondary, fontSize = 12.sp)
                }
                if (viewModel.syncState is SyncState.Running) {
                    CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (configured) {
                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = viewModel.syncState !is SyncState.Running,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BackgroundDark)
                    ) { Text("Sincronizar", fontWeight = FontWeight.Bold) }
                }
                OutlinedButton(
                    onClick = { editUrl = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Servidor")
                }
            }
            if (configured) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.createPairingCode { pairingCode = it } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) {
                    Icon(Icons.Default.Computer, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vincular panel web")
                }
            }
        }
    }

    if (editUrl) ServerUrlDialog(
        current = viewModel.serverUrl,
        onSave = { viewModel.updateServerUrl(it); editUrl = false },
        onDismiss = { editUrl = false }
    )

    pairingCode?.let { code ->
        AlertDialog(
            onDismissRequest = { pairingCode = null },
            containerColor = SurfaceDark,
            title = { Text("Vincular panel web", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Abre ${viewModel.serverUrl} en el ordenador y escribe este código:", color = TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Text(code, color = AccentCyan, fontSize = 32.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    Text("Caduca en 10 minutos y sirve una sola vez. El panel solo puede leer tus datos.", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { pairingCode = null }) { Text("Hecho", color = AccentCyan) } },
            dismissButton = {
                TextButton(onClick = { viewModel.revokePanels { pairingCode = null } }) {
                    Text("Desvincular todos", color = AccentRed)
                }
            }
        )
    }
}

@Composable
private fun ServerUrlDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(current.ifBlank { "https://" }) }
    val valid = url.startsWith("https://") && url.length > 10 || url.startsWith("http://10.0.2.2") || url.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Servidor", color = AccentWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Dirección de tu servidor GymFlow (por ejemplo https://gym.tudominio.com). Déjalo vacío para no sincronizar.",
                    color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim() }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = !valid,
                    supportingText = { if (!valid) Text("Debe empezar por https://") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (url == "https://") "" else url) }, enabled = valid) { Text("Guardar", color = AccentCyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) } }
    )
}

private fun statusText(state: SyncState, pending: Int, configured: Boolean): String {
    if (!configured) return "Tus datos solo están en este móvil"
    val pendingText = if (pending > 0) " · $pending cambios pendientes" else ""
    val time = { at: Long -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) }
    return when (state) {
        SyncState.Idle -> "Pendiente de sincronizar$pendingText"
        SyncState.Running -> "Sincronizando…"
        is SyncState.Done -> "Sincronizado a las ${time(state.at)}$pendingText"
        is SyncState.Failed -> "Error a las ${time(state.at)}: ${state.message}"
    }
}
