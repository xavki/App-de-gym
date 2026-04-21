package com.gymflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════════════════
// CALCULADORA 1RM — Dialog reutilizable
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun OneRMDialog(
    exerciseName: String = "",
    initialWeight: Double = 0.0,
    initialReps: Int = 0,
    currentOneRM: Double = 0.0,   // mejor 1RM del historial (0 = sin datos)
    onDismiss: () -> Unit
) {
    var weightText by remember { mutableStateOf(if (initialWeight > 0) initialWeight.toString() else "") }
    var repsText   by remember { mutableStateOf(if (initialReps > 0)   initialReps.toString()   else "") }

    val weight = weightText.toDoubleOrNull() ?: 0.0
    val reps   = repsText.toIntOrNull()   ?: 0

    val brzycki = if (weight > 0 && reps > 0) oneRMBrzycki(weight, reps) else 0.0
    val epley   = if (weight > 0 && reps > 0) oneRMEpley(weight, reps)   else 0.0
    val lander  = if (weight > 0 && reps > 0) oneRMLander(weight, reps)  else 0.0
    val avg     = if (brzycki > 0) (brzycki + epley + lander) / 3.0 else 0.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0D1A1A)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Título ───────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏋️", fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            stringResource(R.string.orm_title),
                            color = AccentWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp
                        )
                        if (exerciseName.isNotEmpty()) {
                            Text(exerciseName, color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                // ── Inputs ───────────────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it },
                        label = { Text(stringResource(R.string.orm_weight)) },
                        suffix = { Text("kg", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = ormFieldColors()
                    )
                    OutlinedTextField(
                        value = repsText,
                        onValueChange = { repsText = it },
                        label = { Text(stringResource(R.string.orm_reps)) },
                        suffix = { Text("reps", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = ormFieldColors()
                    )
                }

                // ── Resultado principal ──────────────────────────────────────
                if (avg > 0) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF152A2A)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.orm_estimated),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${avg.roundToInt()} kg",
                                color = AccentCyan,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (currentOneRM > 0) {
                                val diff = avg - currentOneRM
                                val diffStr = if (diff >= 0) "+${diff.roundToInt()} kg" else "${diff.roundToInt()} kg"
                                Text(
                                    "${stringResource(R.string.orm_vs_best)} $diffStr",
                                    color = if (diff >= 0) Color(0xFF4CAF50) else AccentRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // ── Tabla de porcentajes ─────────────────────────────────
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1A1A)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.orm_percentage_table),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            listOf(100, 95, 90, 85, 80, 75, 70).forEach { pct ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("$pct%", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${(avg * pct / 100.0).roundToInt()} kg",
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    // Reps estimadas (Prilepin adaptado)
                                    val estReps = when (pct) {
                                        100 -> "1 rep"; 95 -> "2 reps"; 90 -> "3 reps"
                                        85 -> "4-5 reps"; 80 -> "6 reps"; 75 -> "8 reps"
                                        else -> "10+ reps"
                                    }
                                    Text(estReps, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // ── Comparativa de fórmulas ──────────────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneRMFormulaChip("Brzycki", brzycki)
                        OneRMFormulaChip("Epley", epley)
                        OneRMFormulaChip("Lander", lander)
                    }
                }

                // ── Botón cerrar ─────────────────────────────────────────────
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.settings_close), color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OneRMFormulaChip(name: String, value: Double) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A2A)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.wrapContentWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(name, color = TextSecondary, fontSize = 10.sp)
            Text("${value.roundToInt()} kg", color = AccentWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ormFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = Color(0xFF1A2A2A),
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = AccentCyan,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentCyan
)
