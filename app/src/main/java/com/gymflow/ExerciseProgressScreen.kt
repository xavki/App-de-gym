package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════════════════
// GRÁFICA POR EJERCICIO — Progreso de un ejercicio a lo largo del tiempo
// ══════════════════════════════════════════════════════════════════════════════

private data class ExerciseDataPoint(
    val date: Date,
    val maxWeight: Double,
    val totalVolume: Double,
    val estimatedOneRM: Double,
    val bestReps: Int
)

private enum class ExerciseMetric { WEIGHT, ORM, VOLUME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseProgressScreen(
    exerciseName: String,
    viewModel: GymFlowViewModel,
    onBack: () -> Unit
) {
    val history = remember(viewModel.workoutHistory.size, exerciseName) {
        buildExerciseTimeline(exerciseName, viewModel.workoutHistory)
    }

    var metric by remember { mutableStateOf(ExerciseMetric.WEIGHT) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(exerciseName, color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(stringResource(R.string.exercise_progress_subtitle), color = TextSecondary, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (history.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(40.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📈", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.exercise_no_data), color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                // ── Stats summary cards ────────────────────────────────────
                item {
                    val best = history.maxByOrNull { it.maxWeight }
                    val bestORM = history.maxByOrNull { it.estimatedOneRM }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExerciseStatCard("Mejor peso", "${best?.maxWeight ?: 0} kg", "🏋️", AccentCyan, Modifier.weight(1f))
                        ExerciseStatCard("1RM est.", "${bestORM?.estimatedOneRM?.roundToInt() ?: 0} kg", "⚡", Color(0xFFFFD700), Modifier.weight(1f))
                        ExerciseStatCard("Sesiones", "${history.size}", "📅", Color(0xFF4CAF50), Modifier.weight(1f))
                    }
                }

                // ── Selector de métrica ────────────────────────────────────
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricChip("Peso",   metric == ExerciseMetric.WEIGHT) { metric = ExerciseMetric.WEIGHT }
                        MetricChip("1RM",    metric == ExerciseMetric.ORM)    { metric = ExerciseMetric.ORM }
                        MetricChip("Volumen",metric == ExerciseMetric.VOLUME) { metric = ExerciseMetric.VOLUME }
                    }
                }

                // ── Gráfica principal ──────────────────────────────────────
                item {
                    ExerciseLineChart(history = history, metric = metric)
                }

                // ── Tabla de sesiones ──────────────────────────────────────
                item {
                    Text(
                        "HISTORIAL",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                items(history.reversed()) { point ->
                    SessionRow(point)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun MetricChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
            selectedLabelColor = AccentCyan,
            containerColor = Color(0xFF0A0A0A),
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            selectedBorderColor = AccentCyan.copy(alpha = 0.4f),
            borderColor = Color(0xFF1A1A1A)
        )
    )
}

@Composable
private fun ExerciseStatCard(label: String, value: String, emoji: String, color: Color, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = color, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(label, color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ExerciseLineChart(history: List<ExerciseDataPoint>, metric: ExerciseMetric) {
    val sdf = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }
    val values = history.map { pt ->
        when (metric) {
            ExerciseMetric.WEIGHT -> pt.maxWeight.toFloat()
            ExerciseMetric.ORM    -> pt.estimatedOneRM.toFloat()
            ExerciseMetric.VOLUME -> pt.totalVolume.toFloat()
        }
    }
    val unit = when (metric) {
        ExerciseMetric.WEIGHT, ExerciseMetric.ORM -> "kg"
        ExerciseMetric.VOLUME -> "kg·rep"
    }

    val animProgress = remember(metric) { Animatable(0f) }
    LaunchedEffect(metric) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val prog by animProgress.asState()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val metricLabel = when (metric) {
                ExerciseMetric.WEIGHT -> "Peso máximo"
                ExerciseMetric.ORM    -> "1RM estimado"
                ExerciseMetric.VOLUME -> "Volumen total"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metricLabel, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                if (values.size >= 2) {
                    val diff = values.last() - values.first()
                    val sign = if (diff >= 0) "+" else ""
                    Text(
                        "$sign${diff.roundToInt()} $unit",
                        color = if (diff >= 0) Color(0xFF4CAF50) else AccentRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (values.size < 2) {
                Text(stringResource(R.string.exercise_need_more), color = TextSecondary, fontSize = 12.sp)
            } else {
                val maxVal = values.max()
                val minVal = values.min()
                val range = (maxVal - minVal).coerceAtLeast(1f)

                Canvas(
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val step = w / (values.size - 1)

                    val pts = values.mapIndexed { i, v ->
                        Offset(i * step, h - ((v - minVal) / range) * h * 0.82f - h * 0.09f)
                    }

                    // Área bajo la curva
                    val path = Path().apply {
                        moveTo(pts.first().x, h)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, h)
                        close()
                    }
                    drawPath(path, AccentCyan.copy(alpha = 0.08f))

                    // Línea animada
                    val drawCount = (pts.size * prog).toInt().coerceAtMost(pts.size - 1)
                    for (i in 0 until drawCount) {
                        drawLine(AccentCyan, pts[i], pts[i + 1], strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                    // Dots
                    pts.forEachIndexed { i, offset ->
                        if (i <= drawCount) {
                            drawCircle(AccentCyan, 4.dp.toPx(), offset)
                            drawCircle(BackgroundDark, 2.dp.toPx(), offset)
                        }
                    }
                }

                // Etiquetas fechas
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sdf.format(history.first().date), color = TextSecondary, fontSize = 10.sp)
                    Text("${values.max().roundToInt()} $unit", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(sdf.format(history.last().date), color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(point: ExerciseDataPoint) {
    val sdf = remember { SimpleDateFormat("dd/MM/yy", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(sdf.format(point.date), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Text(
                "${point.maxWeight} kg × ${point.bestReps}",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "1RM ${point.estimatedOneRM.roundToInt()} kg",
                color = AccentCyan.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun buildExerciseTimeline(name: String, history: List<WorkoutSession>): List<ExerciseDataPoint> {
    return history
        .sortedBy { it.date }
        .mapNotNull { session ->
            val ex = session.exercises.firstOrNull { it.exerciseName == name } ?: return@mapNotNull null
            val validSets = ex.sets.filter { it.weight > 0 }
            if (validSets.isEmpty()) return@mapNotNull null
            val best = validSets.maxByOrNull { it.weight * it.repetitions.coerceAtLeast(1) }!!
            ExerciseDataPoint(
                date = session.date,
                maxWeight = best.weight,
                totalVolume = validSets.sumOf { it.weight * it.repetitions },
                estimatedOneRM = oneRMBrzycki(best.weight, best.repetitions.coerceAtLeast(1)),
                bestReps = best.repetitions
            )
        }
}
