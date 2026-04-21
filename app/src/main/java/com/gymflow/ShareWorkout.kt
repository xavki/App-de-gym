package com.gymflow

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════════════════
// COMPARTIR WORKOUT — Card visual + Share Intent
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ShareWorkoutDialog(
    summary: WorkoutSummaryData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val today = remember { sdf.format(Date()) }
    val minutes = summary.durationSeconds / 60
    val sets = summary.exercises.sumOf { it.sets.count { s -> s.isCompleted } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF0D1A1A)) {
            Column(modifier = Modifier.padding(0.dp)) {

                // ── Preview de la card ────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFF0A1A1A), Color(0xFF000000))
                            ),
                            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💪", fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("GymFlow", color = AccentCyan, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
                                Text(summary.routineName, color = AccentWhite, fontWeight = FontWeight.Black, fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(today, color = TextSecondary, fontSize = 12.sp)

                        Spacer(Modifier.height(20.dp))

                        // Stats
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ShareStatChip("⏱️", "${minutes}min", "Duración", Modifier.weight(1f))
                            ShareStatChip("🏋️", "${summary.totalSets}", "Series", Modifier.weight(1f))
                            ShareStatChip("🔥", "${summary.totalVolume} kg", "Volumen", Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(16.dp))

                        // Ejercicios resumidos
                        summary.exercises.take(5).forEach { ex ->
                            val completed = ex.sets.filter { it.isCompleted || it.weight > 0 }
                            if (completed.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        ex.exerciseName,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${completed.size} × ${completed.maxOfOrNull { it.weight } ?: 0} kg",
                                        color = AccentCyan,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        if (summary.exercises.size > 5) {
                            Text("+ ${summary.exercises.size - 5} ejercicios más", color = TextSecondary, fontSize = 11.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        // Footer
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                            Spacer(Modifier.width(8.dp))
                            Text("gymflow.app", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                        }
                    }
                }

                // ── Botones ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                    ) { Text(stringResource(R.string.settings_close), color = TextSecondary) }

                    Button(
                        onClick = {
                            shareWorkoutAsText(context, summary)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.share_button), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareStatChip(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(value, color = AccentWhite, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text(label, color = TextSecondary, fontSize = 9.sp)
        }
    }
}

// ── Share como texto plano ────────────────────────────────────────────────────
fun shareWorkoutAsText(context: Context, summary: WorkoutSummaryData) {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("💪 GymFlow — ${summary.routineName}")
    sb.appendLine("📅 ${sdf.format(Date())}")
    sb.appendLine()
    sb.appendLine("⏱️ Duración: ${summary.durationSeconds / 60} min")
    sb.appendLine("🏋️ Series: ${summary.totalSets}")
    sb.appendLine("🔥 Volumen: ${summary.totalVolume} kg")
    sb.appendLine()
    summary.exercises.forEach { ex ->
        val completed = ex.sets.filter { it.isCompleted || it.weight > 0 }
        if (completed.isNotEmpty()) {
            sb.appendLine("• ${ex.exerciseName}")
            completed.forEachIndexed { i, s ->
                sb.appendLine("  S${i + 1}: ${s.weight} kg × ${s.repetitions}")
            }
        }
    }
    sb.appendLine()
    sb.appendLine("Entrenado con GymFlow 🚀")

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, sb.toString())
    }
    context.startActivity(Intent.createChooser(intent, "Compartir entrenamiento"))
}
