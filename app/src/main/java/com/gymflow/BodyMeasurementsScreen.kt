package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════════════════
// PANTALLA DE MEDIDAS CORPORALES
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyMeasurementsScreen(
    viewModel: GymFlowViewModel,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val measurements = viewModel.bodyMeasurements
    val sdf = remember { SimpleDateFormat("dd/MM/yy", Locale.getDefault()) }

    // Latest measurement for display
    val latest = measurements.lastOrNull()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.body_title), color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, null, tint = AccentCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Resumen actual ─────────────────────────────────────────────
            item {
                Spacer(Modifier.height(4.dp))
                if (latest != null) {
                    CurrentStatsCard(latest)
                } else {
                    EmptyMeasurementsHint { showAddDialog = true }
                }
            }

            // ── Gráfica peso ───────────────────────────────────────────────
            if (measurements.size >= 2) {
                item {
                    WeightLineChart(measurements)
                }
            }

            // ── Historial ──────────────────────────────────────────────────
            if (measurements.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.body_history),
                        color = TextSecondary,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
                items(measurements.reversed(), key = { it.id }) { m ->
                    MeasurementCard(
                        measurement = m,
                        dateStr = sdf.format(m.date),
                        onDelete = { viewModel.deleteMeasurement(m) }
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showAddDialog) {
        AddMeasurementDialog(
            onDismiss = { showAddDialog = false },
            onSave = { m ->
                viewModel.saveMeasurement(m)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun CurrentStatsCard(m: BodyMeasurement) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📊", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.body_current),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(sdf.format(m.date), color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))

            // ── Peso y grasa (grandes) ─────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStatCard(
                    value = if (m.weight > 0) "${m.weight} kg" else "—",
                    label = stringResource(R.string.body_weight),
                    emoji = "⚖️",
                    modifier = Modifier.weight(1f)
                )
                BigStatCard(
                    value = if (m.bodyFat > 0) "${m.bodyFat}%" else "—",
                    label = stringResource(R.string.body_fat),
                    emoji = "📉",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── Circunferencias ────────────────────────────────────────
            val measures = listOf(
                stringResource(R.string.body_chest)  to m.chest,
                stringResource(R.string.body_waist)  to m.waist,
                stringResource(R.string.body_hips)   to m.hips,
                stringResource(R.string.body_bicep)  to m.bicep,
                stringResource(R.string.body_thigh)  to m.thigh
            )
            measures.filter { it.second > 0 }.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    row.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(AccentCyan, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("$label:", color = TextSecondary, fontSize = 12.sp)
                            Spacer(Modifier.width(4.dp))
                            Text("$value cm", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BigStatCard(value: String, label: String, emoji: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF152A2A)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = AccentWhite, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyMeasurementsHint(onAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd)
    ) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚖️", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.body_empty_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.body_empty_hint), color = TextSecondary, fontSize = 13.sp)
        }
    }
}

// ── Gráfica de peso ────────────────────────────────────────────────────────────
@Composable
private fun WeightLineChart(measurements: List<BodyMeasurement>) {
    val values = measurements.filter { it.weight > 0 }.map { it.weight.toFloat() }
    if (values.size < 2) return

    val maxVal = values.max()
    val minVal = values.min()
    val range  = (maxVal - minVal).coerceAtLeast(1f)

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(values.size) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }
    val progress by animProgress.asState()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.body_weight_chart),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val w = size.width
                val h = size.height
                val step = if (values.size > 1) w / (values.size - 1).toFloat() else w
                val pts = values.mapIndexed { i, v ->
                    val x = i * step
                    val y = h - ((v - minVal) / range) * h * 0.8f - h * 0.1f
                    Pair(x, y)
                }
                // Draw line
                for (i in 0 until (pts.size - 1).coerceAtMost((pts.size * progress).toInt())) {
                    drawLine(
                        color = AccentCyan,
                        start = androidx.compose.ui.geometry.Offset(pts[i].first, pts[i].second),
                        end   = androidx.compose.ui.geometry.Offset(pts[i + 1].first, pts[i + 1].second),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                // Draw dots
                pts.forEachIndexed { i, (x, y) ->
                    if (i <= (pts.size * progress).toInt()) {
                        drawCircle(color = AccentCyan, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
            }
            // Min/Max labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${minVal.roundToInt()} kg", color = TextSecondary, fontSize = 10.sp)
                Text("${maxVal.roundToInt()} kg", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Card de medición histórica ────────────────────────────────────────────────
@Composable
private fun MeasurementCard(measurement: BodyMeasurement, dateStr: String, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1414)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateStr, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (measurement.weight > 0) {
                    Text("${measurement.weight} kg", color = AccentCyan, fontSize = 12.sp)
                }
            }
            if (measurement.bodyFat > 0) {
                Text("${measurement.bodyFat}%", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// DIALOG: AÑADIR MEDICIÓN
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun AddMeasurementDialog(
    onDismiss: () -> Unit,
    onSave: (BodyMeasurement) -> Unit
) {
    var weight  by remember { mutableStateOf("") }
    var bodyFat by remember { mutableStateOf("") }
    var chest   by remember { mutableStateOf("") }
    var waist   by remember { mutableStateOf("") }
    var hips    by remember { mutableStateOf("") }
    var bicep   by remember { mutableStateOf("") }
    var thigh   by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF0D1A1A)) {
            LazyColumn(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.body_add_title),
                        color = AccentWhite,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }

                // Peso y % grasa
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MeasureField(weight, { weight = it }, stringResource(R.string.body_weight), "kg", Modifier.weight(1f))
                        MeasureField(bodyFat, { bodyFat = it }, stringResource(R.string.body_fat), "%", Modifier.weight(1f))
                    }
                }

                // Circunferencias
                item {
                    Text(
                        stringResource(R.string.body_circumferences),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MeasureField(chest,  { chest  = it }, stringResource(R.string.body_chest), "cm", Modifier.weight(1f))
                    MeasureField(waist,  { waist  = it }, stringResource(R.string.body_waist), "cm", Modifier.weight(1f))
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MeasureField(hips,  { hips  = it }, stringResource(R.string.body_hips),  "cm", Modifier.weight(1f))
                    MeasureField(bicep, { bicep = it }, stringResource(R.string.body_bicep), "cm", Modifier.weight(1f))
                } }
                item {
                    MeasureField(thigh, { thigh = it }, stringResource(R.string.body_thigh), "cm", Modifier.fillMaxWidth(0.5f))
                }

                // Botones
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                        ) { Text(stringResource(R.string.schedule_cancel), color = TextSecondary) }

                        Button(
                            onClick = {
                                onSave(BodyMeasurement(
                                    weight  = weight.toDoubleOrNull()  ?: 0.0,
                                    bodyFat = bodyFat.toDoubleOrNull() ?: 0.0,
                                    chest   = chest.toDoubleOrNull()   ?: 0.0,
                                    waist   = waist.toDoubleOrNull()   ?: 0.0,
                                    hips    = hips.toDoubleOrNull()    ?: 0.0,
                                    bicep   = bicep.toDoubleOrNull()   ?: 0.0,
                                    thigh   = thigh.toDoubleOrNull()   ?: 0.0
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                        ) { Text(stringResource(R.string.schedule_save), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasureField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        suffix = { Text(suffix, color = TextSecondary, fontSize = 11.sp) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = Color(0xFF1A2A2A),
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextSecondary,
            cursorColor = AccentCyan
        )
    )
}
