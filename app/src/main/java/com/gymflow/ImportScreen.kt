package com.gymflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gymflow.synccore.Identity
import com.gymflow.synccore.ItemStatus
import com.gymflow.synccore.Kind

/** Perfil → Importar datos: copia JSON de GymFlow → vista previa (nuevos, existentes, conflictos, inválidos) → confirmación → resultado verificado. */
@Composable
fun ImportDataSection() {
    val vm: ImportSyncViewModel = viewModel()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pickImportFile(it) } }

    Text("IMPORTAR DATOS", color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(12.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Recupera una copia JSON exportada por GymFlow (por ejemplo, de otro móvil). Nunca se sobrescribe nada: " +
                "primero verás qué es nuevo, qué ya existe y qué entra en conflicto.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
            ) {
                Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Importar copia JSON")
            }
        }
    }

    when (val ui = vm.importUi) {
        ImportSyncViewModel.ImportUi.Idle -> {}
        is ImportSyncViewModel.ImportUi.Working -> AlertDialog(
            onDismissRequest = {}, containerColor = SurfaceDark,
            title = { Text("Importar datos", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(ui.text, color = TextSecondary) } },
            confirmButton = {}
        )
        is ImportSyncViewModel.ImportUi.Preview -> {
            val plan = ui.plan
            val choices = vm.importChoices
            AlertDialog(
                onDismissRequest = { vm.closeImport() }, containerColor = SurfaceDark,
                title = { Text("Vista previa de la importación", color = AccentWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Registros detectados: ${plan.items.size + plan.invalidCount}", color = TextPrimary)
                        Text("Nuevos: ${plan.newCount}", color = AccentCyan, fontWeight = FontWeight.Bold)
                        Text("Ya existentes (idénticos, se omiten): ${plan.identicalCount}", color = TextPrimary)
                        Text("En conflicto: ${plan.conflictCount}", color = if (plan.conflictCount > 0) AccentRed else TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Inválidos (no se importan): ${plan.invalidCount}", color = if (plan.invalidCount > 0) AccentRed else TextPrimary)
                        if (plan.identity == Identity.MISMATCH) {
                            Spacer(Modifier.height(8.dp))
                            Text("Este archivo pertenece a OTRA cuenta.", color = AccentRed, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = choices.allowForeignAccount, onCheckedChange = { vm.setAllowForeign(it) })
                                Text("Importarlo igualmente como mío", color = TextPrimary, fontSize = 13.sp)
                            }
                        }
                        plan.warnings.forEach { Spacer(Modifier.height(6.dp)); Text("• $it", color = TextSecondary, fontSize = 12.sp) }
                        val problems = plan.items.filter { it.status == ItemStatus.CONFLICT || it.status == ItemStatus.DELETED_LOCALLY }
                        if (problems.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("Conflictos (por defecto se conserva lo tuyo y no se importa):", color = TextSecondary, fontSize = 12.sp)
                            problems.take(30).forEach { item ->
                                Spacer(Modifier.height(4.dp))
                                Text("${kindName(item.kind)}: ${item.label}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(if (item.status == ItemStatus.DELETED_LOCALLY) "Lo borraste en este móvil" else "Difiere en: ${item.diffs.take(4).joinToString(", ")}", color = TextSecondary, fontSize = 12.sp)
                                if (item.kind != Kind.ACHIEVEMENT) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = item.rootKey in choices.importAsCopy, onCheckedChange = { vm.toggleCopy(item.rootKey) })
                                    Text("Importar también como copia", color = TextPrimary, fontSize = 12.sp)
                                }
                            }
                            if (problems.size > 30) Text("… y ${problems.size - 30} más", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmImport() }, enabled = plan.canApply(choices)) { Text("Importar", color = AccentCyan) }
                },
                dismissButton = { TextButton(onClick = { vm.closeImport() }) { Text("Cancelar", color = TextSecondary) } }
            )
        }
        is ImportSyncViewModel.ImportUi.Done -> AlertDialog(
            onDismissRequest = { vm.closeImport() }, containerColor = SurfaceDark,
            title = { Text("Importación verificada", color = AccentWhite, fontWeight = FontWeight.Bold) },
            text = {
                val r = ui.result
                Column {
                    Text("Registros nuevos: ${r.insertedAggregates} (${r.insertedRows} filas)", color = TextPrimary)
                    Text("Copias de conflictos: ${r.copies}", color = TextPrimary)
                    Text("Omitidos por ser idénticos: ${r.skippedIdentical}", color = TextPrimary)
                    Text("Conflictos sin importar: ${r.skippedConflicts}", color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("Comprobado dentro de la misma operación: todo lo escrito existe, coincide y tiene sus relaciones; nada de lo anterior cambió.", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeImport() }) { Text("Hecho", color = AccentCyan) } }
        )
        is ImportSyncViewModel.ImportUi.Error -> AlertDialog(
            onDismissRequest = { vm.closeImport() }, containerColor = SurfaceDark,
            title = { Text("No se importó nada", color = AccentRed, fontWeight = FontWeight.Bold) },
            text = { Text(ui.message, color = TextPrimary) },
            confirmButton = { TextButton(onClick = { vm.closeImport() }) { Text("Cerrar", color = AccentCyan) } }
        )
    }
}

internal fun kindName(k: Kind) = when (k) {
    Kind.WORKOUT -> "Entrenamiento"
    Kind.ROUTINE -> "Rutina"
    Kind.MEASUREMENT -> "Medida"
    Kind.CUSTOM_EXERCISE -> "Ejercicio"
    Kind.ACHIEVEMENT -> "Logro"
}

/** Datos antiguos de Firestore (H6): nunca se leen solos; solo si el usuario lo pide y confirma. */
@Composable
fun LegacyFirestoreImportCard(viewModel: GymFlowViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val message = viewModel.legacyImportState
    if (!viewModel.legacyImportOffered() && message == null) return
    Spacer(Modifier.height(16.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Datos de la versión anterior (nube antigua)", color = AccentWhite, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Si usabas GymFlow con la nube antigua, puedes traer esos datos a este móvil. Solo ocurre si lo pides aquí. " +
                "No se sobrescribe nada de lo que ya tengas.", color = TextSecondary, fontSize = 13.sp)
            if (message != null) { Spacer(Modifier.height(8.dp)); Text(message, color = AccentCyan, fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)) { Text("Importar datos antiguos") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = SurfaceDark,
        title = { Text("¿Importar datos antiguos?", color = AccentWhite, fontWeight = FontWeight.Bold) },
        text = { Text("Se leerán tus datos antiguos de la nube de tu cuenta actual y se añadirán a este móvil sin sobrescribir nada.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirm = false; viewModel.importLegacyFirestore() }) { Text("Importar", color = AccentCyan) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar", color = TextSecondary) } }
    )
}
