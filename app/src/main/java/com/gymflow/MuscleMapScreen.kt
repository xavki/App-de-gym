package com.gymflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════
// Mapa de grupos musculares → keywords para mapear ejercicios
// ════════════════════════════════════════════════════════════════════════════

private data class MuscleGroupDef(
    val id: String,
    val labelEs: String,
    val labelEn: String,
    val keywords: List<String>
)

private val muscleGroups = listOf(
    MuscleGroupDef("chest",     "Pecho",          "Chest",        listOf("press", "chest", "pecho", "fly", "dip")),
    MuscleGroupDef("back",      "Espalda",        "Back",         listOf("row", "pull", "lat", "deadlift", "back", "espalda", "chin")),
    MuscleGroupDef("shoulders", "Hombros",        "Shoulders",    listOf("shoulder", "overhead", "lateral raise", "hombro", "delts", "press arnold")),
    MuscleGroupDef("biceps",    "Bíceps",         "Biceps",       listOf("curl", "bicep", "bícep")),
    MuscleGroupDef("triceps",   "Tríceps",        "Triceps",      listOf("tricep", "trícep", "pushdown", "extension", "dip")),
    MuscleGroupDef("abs",       "Abdominales",    "Abs",          listOf("crunch", "sit-up", "abs", "plank", "core", "abdominales")),
    MuscleGroupDef("quads",     "Cuádriceps",     "Quads",        listOf("squat", "leg press", "lunge", "quad", "sentadilla")),
    MuscleGroupDef("hamstrings","Isquiotibiales", "Hamstrings",   listOf("deadlift", "hamstring", "curl", "leg curl", "isquio")),
    MuscleGroupDef("glutes",    "Glúteos",        "Glutes",       listOf("glute", "hip thrust", "bridge", "glúteo")),
    MuscleGroupDef("calves",    "Gemelos",        "Calves",       listOf("calf", "raise", "gemelo")),
    MuscleGroupDef("forearms",  "Antebrazos",     "Forearms",     listOf("forearm", "wrist", "antebrazo")),
    MuscleGroupDef("cardio",    "Cardio",         "Cardio",       listOf("run", "cardio", "treadmill", "bike", "rowing", "burpee", "jump"))
)

// ════════════════════════════════════════════════════════════════════════════
// Calcula intensidad por grupo (0.0 a 1.0) en los últimos 7 días
// ════════════════════════════════════════════════════════════════════════════

fun computeMuscleIntensity(history: List<WorkoutSession>): Map<String, Float> {
    val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time
    val recentSessions = history.filter { it.date.after(sevenDaysAgo) }

    val countMap = mutableMapOf<String, Int>()
    recentSessions.forEach { session ->
        session.exercises.forEach { ex ->
            val name = ex.exerciseName.lowercase()
            muscleGroups.forEach { mg ->
                if (mg.keywords.any { kw -> name.contains(kw) }) {
                    countMap[mg.id] = (countMap[mg.id] ?: 0) + ex.sets.filter { it.isCompleted || it.weight > 0 }.size
                }
            }
        }
    }
    val maxSets = (countMap.values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    return countMap.mapValues { (_, v) -> (v.toFloat() / maxSets).coerceIn(0f, 1f) }
}

// ════════════════════════════════════════════════════════════════════════════
// MuscleMap Composable
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MuscleMapCard(
    history: List<WorkoutSession>,
    isEnglish: Boolean = false
) {
    val intensity = remember(history.size) { computeMuscleIntensity(history) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Título
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗺️", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.muscle_map_title),
                    color = TextSecondary,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.muscle_map_subtitle),
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            // Silueta + barras
            Row(modifier = Modifier.fillMaxWidth()) {
                // FRONTAL
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FrontalSilhouette(intensity)
                }
                Spacer(Modifier.width(12.dp))
                // BARRAS de intensidad
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Spacer(Modifier.height(4.dp))
                    muscleGroups.forEach { mg ->
                        val value = intensity[mg.id] ?: 0f
                        val label = if (isEnglish) mg.labelEn else mg.labelEs
                        MuscleIntensityBar(label, value)
                    }
                }
            }

            // Leyenda
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                LegendItem(Color(0xFF1A2A2A), stringResource(R.string.muscle_none))
                LegendItem(AccentCyan.copy(alpha = 0.5f), stringResource(R.string.muscle_light))
                LegendItem(AccentCyan, stringResource(R.string.muscle_high))
            }
        }
    }
}

@Composable
private fun MuscleIntensityBar(label: String, intensity: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(80.dp))
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(Color(0xFF0A1010), RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(intensity.coerceAtLeast(if (intensity > 0) 0.05f else 0f))
                    .background(
                        AccentCyan.copy(alpha = 0.3f + intensity * 0.7f),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}

// ── Silueta frontal simplificada ──────────────────────────────────────────────
@Composable
private fun FrontalSilhouette(intensity: Map<String, Float>) {
    Canvas(
        modifier = Modifier
            .width(100.dp)
            .height(200.dp)
    ) {
        val w = size.width
        val h = size.height

        fun colorFor(id: String): Color {
            val v = intensity[id] ?: 0f
            return if (v == 0f) Color(0xFF1A2A2A)
            else AccentCyan.copy(alpha = 0.25f + v * 0.75f)
        }

        // Cabeza
        drawCircle(color = Color(0xFF1E2E2E), radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.05f))

        // Cuello
        drawRect(color = Color(0xFF1A2A2A), topLeft = Offset(w * 0.43f, h * 0.09f), size = Size(w * 0.14f, h * 0.05f))

        // Pecho
        drawRect(color = colorFor("chest"), topLeft = Offset(w * 0.25f, h * 0.13f), size = Size(w * 0.50f, h * 0.12f), alpha = 0.9f)

        // Hombros
        drawOval(color = colorFor("shoulders"), topLeft = Offset(w * 0.05f, h * 0.12f), size = Size(w * 0.22f, h * 0.08f))
        drawOval(color = colorFor("shoulders"), topLeft = Offset(w * 0.73f, h * 0.12f), size = Size(w * 0.22f, h * 0.08f))

        // Bíceps
        drawRect(color = colorFor("biceps"), topLeft = Offset(w * 0.04f, h * 0.21f), size = Size(w * 0.14f, h * 0.14f))
        drawRect(color = colorFor("biceps"), topLeft = Offset(w * 0.82f, h * 0.21f), size = Size(w * 0.14f, h * 0.14f))

        // Tríceps (a los lados)
        drawRect(color = colorFor("triceps"), topLeft = Offset(w * 0.01f, h * 0.20f), size = Size(w * 0.06f, h * 0.13f))
        drawRect(color = colorFor("triceps"), topLeft = Offset(w * 0.93f, h * 0.20f), size = Size(w * 0.06f, h * 0.13f))

        // Abdominales
        drawRect(color = colorFor("abs"), topLeft = Offset(w * 0.30f, h * 0.26f), size = Size(w * 0.40f, h * 0.15f))

        // Antebrazos
        drawRect(color = colorFor("forearms"), topLeft = Offset(w * 0.03f, h * 0.36f), size = Size(w * 0.12f, h * 0.10f))
        drawRect(color = colorFor("forearms"), topLeft = Offset(w * 0.85f, h * 0.36f), size = Size(w * 0.12f, h * 0.10f))

        // Cadera / glúteos (frente baja)
        drawRect(color = colorFor("glutes"), topLeft = Offset(w * 0.28f, h * 0.42f), size = Size(w * 0.44f, h * 0.07f))

        // Cuádriceps
        drawRect(color = colorFor("quads"), topLeft = Offset(w * 0.26f, h * 0.50f), size = Size(w * 0.20f, h * 0.18f))
        drawRect(color = colorFor("quads"), topLeft = Offset(w * 0.54f, h * 0.50f), size = Size(w * 0.20f, h * 0.18f))

        // Gemelos
        drawRect(color = colorFor("calves"), topLeft = Offset(w * 0.28f, h * 0.70f), size = Size(w * 0.17f, h * 0.14f))
        drawRect(color = colorFor("calves"), topLeft = Offset(w * 0.55f, h * 0.70f), size = Size(w * 0.17f, h * 0.14f))

        // Cardio (corazón en el pecho)
        if ((intensity["cardio"] ?: 0f) > 0f) {
            drawCircle(
                color = Color(0xFFFF6B6B).copy(alpha = (intensity["cardio"] ?: 0f) * 0.8f),
                radius = w * 0.06f,
                center = Offset(w * 0.5f, h * 0.18f)
            )
        }
    }
}
