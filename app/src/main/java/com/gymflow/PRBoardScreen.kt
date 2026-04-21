package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════════════════
// PR BOARD — Récords personales por ejercicio
// ══════════════════════════════════════════════════════════════════════════════

data class ExercisePR(
    val name: String,
    val bestWeight: Double,
    val bestReps: Int,
    val bestVolume: Double,   // peso × reps del mejor set individual
    val estimatedOneRM: Double,
    val date: Date,
    val progressPoints: List<Float>  // historial de volumen normalizado para sparkline
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PRBoardScreen(
    viewModel: GymFlowViewModel,
    onBack: () -> Unit
) {
    val prs = remember(viewModel.workoutHistory.size) {
        computePRs(viewModel.workoutHistory)
    }

    var sortBy by remember { mutableStateOf("volume") } // "volume" | "orm" | "name"
    val sorted = remember(prs, sortBy) {
        when (sortBy) {
            "orm"  -> prs.sortedByDescending { it.estimatedOneRM }
            "name" -> prs.sortedBy { it.name }
            else   -> prs.sortedByDescending { it.bestVolume }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🏆", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pr_board_title), color = AccentWhite, fontWeight = FontWeight.Bold)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                // ── Filtros ────────────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortChip("Volumen",  selected = sortBy == "volume") { sortBy = "volume" }
                    SortChip("1RM est.", selected = sortBy == "orm")    { sortBy = "orm" }
                    SortChip("A-Z",      selected = sortBy == "name")   { sortBy = "name" }
                }
                Spacer(Modifier.height(8.dp))
                if (prs.isEmpty()) {
                    EmptyPRHint()
                }
            }

            itemsIndexed(sorted) { index, pr ->
                PRCard(pr, rank = index + 1)
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun PRCard(pr: ExercisePR, rank: Int) {
    val sdf = remember { SimpleDateFormat("dd MMM yy", Locale.getDefault()) }
    val medalEmoji = when (rank) {
        1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "#$rank"
    }
    val isTop3 = rank <= 3

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(pr.name) {
        animProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isTop3) Color(0xFF0D1A1A) else Color(0xFF0A0A0A)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Medalla / número
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isTop3) AccentCyan.copy(alpha = 0.1f) else Color(0xFF1A1A1A),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        medalEmoji,
                        fontSize = if (isTop3) 18.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(pr.name, color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    Text(sdf.format(pr.date), color = TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Stats row ─────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRStat(
                    label = "Mejor set",
                    value = "${pr.bestWeight}kg × ${pr.bestReps}",
                    color = AccentCyan,
                    modifier = Modifier.weight(1f)
                )
                PRStat(
                    label = "1RM est.",
                    value = "${pr.estimatedOneRM.roundToInt()} kg",
                    color = Color(0xFFFFD700),
                    modifier = Modifier.weight(1f)
                )
                PRStat(
                    label = "Volumen",
                    value = "${pr.bestVolume.roundToInt()} kg",
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Sparkline ─────────────────────────────────────────────────
            if (pr.progressPoints.size >= 2) {
                Spacer(Modifier.height(10.dp))
                SparkLine(
                    points = pr.progressPoints,
                    progress = animProgress.value,
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                )
            }
        }
    }
}

@Composable
private fun PRStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1010)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = TextSecondary, fontSize = 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SparkLine(points: List<Float>, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val min = points.min()
        val max = points.max()
        val range = (max - min).coerceAtLeast(1f)
        val step = if (points.size > 1) w / (points.size - 1) else w
        val pts = points.mapIndexed { i, v ->
            Offset(i * step, h - ((v - min) / range) * h * 0.8f - h * 0.1f)
        }
        val drawUntil = (pts.size * progress).toInt().coerceAtMost(pts.size - 1)
        for (i in 0 until drawUntil) {
            drawLine(AccentCyan, pts[i], pts[i + 1], strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        if (drawUntil < pts.size) {
            drawCircle(AccentCyan, 4.dp.toPx(), pts[drawUntil])
        }
    }
}

@Composable
private fun EmptyPRHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(40.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🏆", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pr_empty_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.pr_empty_hint), color = TextSecondary, fontSize = 13.sp)
        }
    }
}

// ── Lógica de cálculo de PRs ──────────────────────────────────────────────────
fun computePRs(history: List<WorkoutSession>): List<ExercisePR> {
    // Agrupa todos los ejercicios de todo el historial
    val exerciseMap = mutableMapOf<String, MutableList<Pair<Date, ExerciseSet>>>()
    history.sortedBy { it.date }.forEach { session ->
        session.exercises.forEach { ex ->
            ex.sets.filter { it.weight > 0 || it.repetitions > 0 }.forEach { set ->
                exerciseMap.getOrPut(ex.exerciseName) { mutableListOf() }
                    .add(session.date to set)
            }
        }
    }

    return exerciseMap.mapNotNull { (name, entries) ->
        if (entries.isEmpty()) return@mapNotNull null
        val bestEntry = entries.maxByOrNull { (_, s) -> s.weight * s.repetitions } ?: return@mapNotNull null
        val (bestDate, bestSet) = bestEntry
        val orm = oneRMBrzycki(bestSet.weight, bestSet.repetitions.coerceAtLeast(1))

        // Sparkline: volumen por sesión (agrupado por día)
        val byDay = entries.groupBy { (d, _) -> d.time / 86400000 }
            .values.map { dayEntries -> dayEntries.maxOf { (_, s) -> (s.weight * s.repetitions).toFloat() } }

        ExercisePR(
            name             = name,
            bestWeight       = bestSet.weight,
            bestReps         = bestSet.repetitions,
            bestVolume       = bestSet.weight * bestSet.repetitions,
            estimatedOneRM   = orm,
            date             = bestDate,
            progressPoints   = byDay
        )
    }
}
