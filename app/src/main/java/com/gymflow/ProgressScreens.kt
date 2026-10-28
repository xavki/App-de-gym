package com.gymflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// Piezas comunes de las secciones nuevas
// ═══════════════════════════════════════════════════════════════════════════════

private val es = Locale("es", "ES")
fun fmtKg(v: Double): String = if (v % 1.0 == 0.0) "${v.toLong()} kg" else String.format(es, "%.1f kg", v)
/** 12.400 kg → "12,4 t"; por debajo de 10 t, en kg (igual que el panel). */
fun fmtVolume(kg: Double): String = if (kg >= 10_000) String.format(es, "%.1f t", kg / 1000) else String.format(es, "%,.0f kg", kg)
fun fmtDuration(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600 + 30) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}
private fun fmtDay(ms: Long) = SimpleDateFormat("EEE d MMM", es).format(ms).replaceFirstChar { it.uppercase() }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape)
            .border(1.dp, Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun StatTile(icon: ImageVector, label: String, value: String, hint: String? = null, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Box(Modifier.size(36.dp).background(AccentCyan.copy(alpha = 0.12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun EmptyCard(icon: ImageVector, title: String, text: String) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).background(SurfaceAlt, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(text, color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun WorkoutRow(w: WorkoutSession, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tamaño mínimo (no fijo): con el texto grande del sistema la fecha crece en vez de cortarse
        Column(
            Modifier.defaultMinSize(48.dp, 48.dp).background(AccentCyan.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text(SimpleDateFormat("d", es).format(w.date), color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(SimpleDateFormat("MMM", es).format(w.date).uppercase(), color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name.ifBlank { "Entreno" }, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${fmtVolume(TrainingStats.volume(w))} · ${TrainingStats.workSets(w)} series" +
                    (if (w.durationSeconds > 0) " · ${fmtDuration(w.durationSeconds.toLong())}" else ""),
                color = TextSecondary, fontSize = 13.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// INICIO — último entreno, acceso rápido y resumen de la semana (sin sobrecargar)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun HomeTab(viewModel: GymFlowViewModel, onContinueWorkout: () -> Unit, onGoTrain: () -> Unit, onGoProgress: () -> Unit) {
    val history = viewModel.workoutHistory
    val now = System.currentTimeMillis()
    val week = remember(history.size) { TrainingStats.inPeriod(history, TrainingStats.Period.WEEK, now) }
    val sorted = remember(history.size) { history.sortedByDescending { it.date.time } }
    val active = viewModel.activeWorkout

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Hola 👋", color = TextSecondary, fontSize = 15.sp)
            Text("¿Entrenamos?", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
        item {
            if (active != null && viewModel.workoutRecovered) {
                // Entreno a medias recuperado tras cerrar la app: continuar o descartar
                AppCard {
                    Text("Tienes un entreno sin terminar", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Se ha recuperado «${active.name}» tal como lo dejaste.", color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.discardWorkout() }) { Text("Descartar", color = AccentRed, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (active != null) {
                Button(
                    onClick = onContinueWorkout, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = OnAccent)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continuar «${active.name}» · ${fmtDuration(viewModel.totalSeconds.toLong())}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Button(
                    onClick = onGoTrain, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = OnAccent)
                ) {
                    Icon(Icons.Default.FitnessCenter, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Empezar a entrenar", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            val s = TrainingStats.summary(week, TrainingStats.Period.WEEK, now, history)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Default.CalendarMonth, "Esta semana", "${s.workouts}", if (s.workouts == 1) "entreno" else "entrenos", Modifier.weight(1f))
                StatTile(Icons.Default.Layers, "Volumen", fmtVolume(s.totalVolumeKg), "${s.workSets} series", Modifier.weight(1f))
            }
        }
        item {
            val last = sorted.firstOrNull()
            if (last == null) {
                EmptyCard(Icons.Default.FitnessCenter, "Aún no hay entrenos", "Empieza una rutina y aquí verás tu actividad.")
            } else {
                AppCard {
                    Text("Actividad reciente", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    sorted.take(4).forEach { WorkoutRow(it) }
                }
            }
        }
        if (history.isNotEmpty()) item {
            AppCard(onClick = onGoProgress) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ShowChart, null, tint = AccentCyan)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ver tu progreso", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Volumen, récords y grupos musculares", color = TextSecondary, fontSize = 13.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROGRESO — estadísticas con los datos locales (funciona sin conexión)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ProgressTab(viewModel: GymFlowViewModel, onOpenPRBoard: () -> Unit, onExerciseProgress: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf(TrainingStats.Period.QUARTER) }
    val history = viewModel.workoutHistory
    val now = remember(history.size) { System.currentTimeMillis() }
    // Grupo muscular de cada ejercicio: catálogo (y ejercicios personalizados)
    val groups = remember(viewModel.customExercises.size) {
        ExerciseRepository.getCached().associate { it.name to ExerciseNamesEs.group(it.mainGroup) } +
            viewModel.customExercises.associate { it.name to it.mainGroup }
    }
    // Todo se recalcula solo al cambiar el periodo o el historial, no en cada recomposición
    val inPeriod = remember(history.size, period) { TrainingStats.inPeriod(history, period, now) }
    val summary = remember(inPeriod) { TrainingStats.summary(inPeriod, period, now, history) }
    val buckets = remember(inPeriod) { TrainingStats.buckets(inPeriod, period, now, history) }
    val byGroup = remember(inPeriod, groups) { TrainingStats.setsByGroup(inPeriod) { groups[it] } }
    val records = remember(history.size) { TrainingStats.records(history) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Progreso") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainingStats.Period.entries.forEach { p ->
                    FilterChip(
                        selected = p == period, onClick = { period = p }, label = { Text(p.label) },
                        modifier = Modifier.heightIn(min = 40.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan, selectedLabelColor = OnAccent,
                            containerColor = SurfaceDark, labelColor = TextPrimary
                        )
                    )
                }
            }
        }
        if (history.isEmpty()) {
            item { EmptyCard(Icons.Default.ShowChart, "Sin datos todavía", "Cuando registres entrenos verás aquí tu evolución.") }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Default.FitnessCenter, "Entrenos", "${summary.workouts}",
                    summary.perWeek?.let { String.format(es, "%.1f por semana", it) }, Modifier.weight(1f))
                StatTile(Icons.Default.Layers, "Volumen", fmtVolume(summary.totalVolumeKg), "Peso × reps", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Default.Done, "Series efectivas", "${summary.workSets}", "Sin calentamientos", Modifier.weight(1f))
                StatTile(Icons.Default.Timer, "Duración media",
                    if (summary.avgSeconds > 0) fmtDuration(summary.avgSeconds) else "—",
                    if (summary.totalSeconds > 0) "Total ${fmtDuration(summary.totalSeconds)}" else null, Modifier.weight(1f))
            }
        }
        item {
            val (monthly, list) = buckets
            AppCard {
                Text(if (monthly) "Volumen por mes" else "Volumen por semana", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Peso × repeticiones de las series efectivas", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                VolumeBars(list.map { it.volumeKg }, list.map {
                    SimpleDateFormat(if (monthly) "MMM" else "d MMM", es).format(it.startMs)
                })
            }
        }
        if (byGroup.isNotEmpty()) item {
            AppCard {
                Text("Series por grupo muscular", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Cada serie cuenta una vez, en el grupo principal del ejercicio", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                val max = byGroup.maxOf { it.second }.coerceAtLeast(1)
                byGroup.take(10).forEach { (g, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(10.dp).background(SurfaceAlt, RoundedCornerShape(5.dp))) {
                            Box(Modifier.fillMaxWidth(n / max.toFloat()).fillMaxHeight().background(AccentTeal, RoundedCornerShape(5.dp)))
                        }
                        Text("$n", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(36.dp).padding(start = 8.dp))
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Récords personales", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onOpenPRBoard) { Text("Ver todos", color = AccentCyan, fontWeight = FontWeight.Bold) }
            }
            Text("De todo el historial. Peso máximo = lo levantado; 1RM = estimación (Brzycki, series de 1 a 12 reps).",
                color = TextSecondary, fontSize = 12.sp)
        }
        if (records.isEmpty()) item {
            EmptyCard(Icons.Default.EmojiEvents, "Sin récords con peso", "Registra series con peso para ver tus marcas.")
        }
        items(records.take(8), key = { it.exerciseName }) { r ->
            AppCard(onClick = { onExerciseProgress(r.exerciseName) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ExerciseNamesEs.display(r.exerciseName), color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.sessions} sesiones · máx. ${fmtKg(r.topWeight)} × ${r.topWeightReps} · ${fmtDay(r.topWeightDateMs)}",
                            color = TextSecondary, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (r.bestE1rm > 0) fmtKg(Math.round(r.bestE1rm * 10) / 10.0) else "—", color = AccentCyan, fontWeight = FontWeight.Bold)
                        Text("1RM est.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, "Ver progreso", tint = TextSecondary)
                }
            }
        }
        item {
            val catalog = remember(viewModel.customExercises.size, ExerciseRepository.getCached().size) { muscleCatalog(ExerciseRepository.getCached(), viewModel.customExercises) }
            MuscleMapCard(history = history, catalog = catalog)
        }
    }
}

/** Columnas de volumen; se dibujan con Canvas (sin librerías de gráficos). */
@Composable
private fun VolumeBars(values: List<Double>, labels: List<String>) {
    val pal = LocalPalette.current
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val desc = values.zip(labels).joinToString { (v, l) -> "$l: ${fmtVolume(v)}" }
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Volumen: $desc" }) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / max * (size.height - 4.dp.toPx())).toFloat()
            drawRoundRect(
                color = if (v == max) pal.accent else pal.accent.copy(alpha = 0.45f),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h), cornerRadius = CornerRadius(4.dp.toPx())
            )
        }
    }
    // Etiquetas: primera, central y última, para que no se pisen en pantallas estrechas
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        listOf(0, labels.size / 2, labels.lastIndex).distinct().filter { it in labels.indices }.forEachIndexed { i, idx ->
            Text(labels[idx], color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End })
        }
    }
    Text("Máximo: ${fmtVolume(max)}", color = TextSecondary, fontSize = 12.sp)
}
