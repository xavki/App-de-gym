package com.gymflow

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════════
// Utilidades de sistema: vibración y sonido
// ═══════════════════════════════════════════════════════════════════════════════

/** Vibración corta de 300 ms */
fun vibrateDevice(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        mgr.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(300)
    }
}

/** Vibración larga con patrón (descanso terminado) */
fun vibratePattern(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        mgr.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    val pattern = longArrayOf(0, 200, 100, 200, 100, 400)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(pattern, -1)
    }
}

/** Sonido "ding" corto al completar serie */
fun playDingSound() {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        // Release después de que suene
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toneGen.release() }, 300)
    } catch (_: Exception) { /* no-op si falla */ }
}

/** Peso de referencia ("Anterior", "Última sesión"): un decimal con coma, sin ",0". */
fun fmtPrev(w: Double): String { val r = Math.round(w * 10) / 10.0; return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString().replace('.', ',') }

/** Peso para los campos: "70" en vez de "70.0" y coma decimal ("72,5"). */
fun fmtWeightInput(w: Double): String =
    if (w % 1.0 == 0.0) w.toLong().toString() else w.toString().replace('.', ',')

// ═══════════════════════════════════════════════════════════════════════════════
// WORKOUT SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    routine: WorkoutSession,
    totalSeconds: Int,
    onFinish: () -> Unit,
    onMinimize: () -> Unit,
    onInfoClick: (WorkoutExercise) -> Unit,
    viewModel: GymFlowViewModel
) {
    val context = LocalContext.current
    val currentIndex = viewModel.activeWorkoutExerciseIndex
    val ex = routine.exercises.getOrNull(currentIndex)
    var showRestTimer by remember { mutableStateOf(false) }
    val definition = ExerciseRepository.getCached().find { it.name == ex?.exerciseName }
    
    val isCardio = definition?.mainGroup?.equals("cardio", ignoreCase = true) == true

    // ── Autocompletar desde última sesión ────────────────────────────────────
    val lastSessionSets = remember(ex?.exerciseName) {
        ex?.exerciseName?.let { name ->
            viewModel.workoutHistory
                .sortedByDescending { it.date }
                .firstOrNull { session -> session.exercises.any { it.exerciseName == name } }
                ?.exercises?.firstOrNull { it.exerciseName == name }
                ?.sets?.filter { it.weight > 0 || it.repetitions > 0 }
        } ?: emptyList()
    }

    // SnapshotStateList para que Compose detecte cambios
    val currentSets = remember(ex?.id) { 
        mutableStateListOf<ExerciseSet>().apply { 
            ex?.sets?.let { addAll(it) }
            // Autocompletar valores de última sesión si los sets están vacíos
            if (lastSessionSets.isNotEmpty()) {
                forEachIndexed { i, set ->
                    val prev = lastSessionSets.getOrNull(i)
                    if (prev != null && set.weight == 0.0 && set.repetitions == 0) {
                        this[i] = set.copy(weight = prev.weight, repetitions = prev.repetitions)
                        ex?.sets?.getOrNull(i)?.also { s ->
                            s.weight = prev.weight
                            s.repetitions = prev.repetitions
                        }
                    }
                }
            }
        } 
    }

    // Sincronizamos los cambios de vuelta al objeto original
    // Cualquier cambio (añadir, quitar, marcar, cambiar tipo) se copia al entreno: los mismos objetos,
    // así que también los pesos y repeticiones que se escriben. Antes solo se copiaba al cambiar el
    // número de series y se perdían las marcas de "hecha" y los tipos de serie.
    LaunchedEffect(currentSets.toList()) {
        ex?.let {
            it.sets.clear()
            it.sets.addAll(currentSets)
        }
        viewModel.saveDraftSoon()
    }

    // ── Contador de volumen total ────────────────────────────────────────
    val totalVolume = remember(currentSets.toList()) {
        routine.exercises.sumOf { exercise ->
            exercise.sets
                .filter { it.isCompleted || it.weight > 0 }
                .sumOf { (it.weight * it.repetitions).toInt() }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val m = (totalSeconds % 3600) / 60
                        val s = totalSeconds % 60
                        Text(
                            String.format(Locale.getDefault(), "%02d:%02d", m, s), 
                            color = AccentCyan, 
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(16.dp))
                        // Volumen total
                        Text(
                            "${totalVolume} kg",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onMinimize) { Icon(Icons.Default.ExpandMore, null, tint = AccentWhite) } },
                actions = {
                    RestChip(viewModel) { showRestTimer = true }
                    var confirmDiscard by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Default.DeleteOutline, "Descartar entreno", tint = TextSecondary) }
                    if (confirmDiscard) {
                        AlertDialog(
                            onDismissRequest = { confirmDiscard = false },
                            containerColor = SurfaceDark,
                            title = { Text("¿Descartar el entreno?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = { Text("No se guardará nada de esta sesión.", color = TextSecondary) },
                            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardWorkout(); onMinimize() }) { Text("Descartar", color = AccentRed, fontWeight = FontWeight.Bold) } },
                            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir entrenando", color = AccentCyan) } }
                        )
                    }
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.workout_finish), color = AccentCyan, fontWeight = FontWeight.Black) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 24.dp)) {
            if (ex != null) {
                // ── Barra de progreso del workout ────────────────────────
                val progress = (currentIndex + 1).toFloat() / routine.exercises.size.toFloat()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.workout_exercise_of, currentIndex + 1, routine.exercises.size),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AccentCyan,
                    trackColor = SurfaceAlt
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        ExerciseImageAnimated(
                            imageUrl = definition?.imageUrl,
                            gifUrl   = definition?.gifUrl,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ExerciseNamesEs.display(ex.exerciseName),
                                color = TextPrimary,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.weight(1f)
                            )
                            // Calculadora 1RM
                            var showOneRM by remember { mutableStateOf(false) }
                            IconButton(onClick = { showOneRM = true }) {
                                Icon(Icons.Default.Calculate, null, tint = AccentCyan.copy(alpha = 0.7f))
                            }
                            IconButton(onClick = {
                                // Sin descanso en marcha, el botón empieza uno nuevo con la duración configurada
                                if (viewModel.restStatus == RestTimer.Status.IDLE || viewModel.restStatus == RestTimer.Status.FINISHED) viewModel.startRest()
                                showRestTimer = true
                            }) {
                                Icon(Icons.Default.Timer, "Descanso", tint = AccentCyan)
                            }
                            IconButton(onClick = { onInfoClick(ex) }) {
                                Icon(Icons.Outlined.Info, null, tint = AccentCyan)
                            }
                            if (showOneRM) {
                                OneRMDialog(
                                    exerciseName = ex.exerciseName,
                                    currentOneRM  = viewModel.estimatedOneRM(ex.exerciseName),
                                    onDismiss     = { showOneRM = false }
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))

                        // ── Notas del ejercicio ─────────────────────────────
                        var notesText by remember(ex.id) { mutableStateOf(ex.notes ?: "") }
                        OutlinedTextField(
                            value = notesText,
                            onValueChange = { notesText = it; ex.notes = it; viewModel.saveDraftSoon() },
                            label = { Text("📝 Notas", fontSize = 11.sp) },
                            placeholder = { Text("Técnica, sensaciones...", fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.4f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = AccentCyan.copy(alpha = 0.5f),
                                unfocusedBorderColor = SurfaceAlt,
                                focusedTextColor     = TextPrimary,
                                unfocusedTextColor   = TextPrimary,
                                focusedLabelColor    = AccentCyan,
                                unfocusedLabelColor  = TextSecondary,
                                cursorColor          = AccentCyan
                            )
                        )
                        Spacer(Modifier.height(12.dp))

                        // ── Historial de la última sesión ────────────────────
                        if (lastSessionSets.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Última sesión: ", color = TextSecondary, fontSize = 11.sp)
                                lastSessionSets.take(3).forEachIndexed { i, s ->
                                    if (i > 0) Text(" · ", color = TextSecondary, fontSize = 11.sp)
                                    Text("${fmtPrev(s.weight)}×${s.repetitions}", color = AccentCyan.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                if (lastSessionSets.size > 3) Text(" ...", color = TextSecondary, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (isCardio) R.string.workout_sessions else R.string.workout_sets), color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { 
                                currentSets.add(ExerciseSet(id = UUID.randomUUID().toString())) 
                            }) {
                                Icon(Icons.Default.Add, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.workout_add), color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    items(items = currentSets, key = { it.id }) { set ->
                        val index = currentSets.indexOf(set)
                        val isCurrent = index == currentSets.indexOfFirst { !it.isCompleted }
                        val isWarmup = set.setType == SetType.WARMUP
                        // Serie de la sesión anterior en la misma posición (solo si existe)
                        val prev = lastSessionSets.getOrNull(index)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    set.isCompleted -> AccentTeal.copy(alpha = 0.10f)
                                    isWarmup -> SurfaceAlt
                                    else -> SurfaceDark
                                }
                            ),
                            border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, AccentCyan) else null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .semantics(mergeDescendants = false) {
                                    contentDescription = buildString {
                                        append(if (isWarmup) "Calentamiento" else "Serie ${index + 1}")
                                        if (isCurrent) append(", serie actual")
                                        if (set.isCompleted) append(", hecha")
                                    }
                                }
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Badge de tipo de serie (pulsable para cambiar)
                                var showSetTypePicker by remember { mutableStateOf(false) }
                                val setTypeColor = when (set.setType) {
                                    SetType.WARMUP   -> Gold
                                    SetType.DROP_SET -> Color(0xFFFF6B35)
                                    SetType.FAILURE  -> Color(0xFF9C27B0)
                                    else             -> if (set.isCompleted) AccentTeal else AccentCyan
                                }
                                val setTypeLabel = when (set.setType) {
                                    SetType.WARMUP   -> "C"
                                    SetType.DROP_SET -> "D"
                                    SetType.FAILURE  -> "F"
                                    else             -> "${index + 1}"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(setTypeColor, CircleShape)
                                        .clickable(onClickLabel = "Cambiar tipo de serie") { showSetTypePicker = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(setTypeLabel, color = OnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                if (showSetTypePicker) {
                                    SetTypePickerDialog(
                                        current = set.setType,
                                        onSelect = { newType ->
                                            val idx2 = currentSets.indexOf(set)
                                            if (idx2 != -1) currentSets[idx2] = currentSets[idx2].copy(setType = newType)
                                            showSetTypePicker = false
                                        },
                                        onDismiss = { showSetTypePicker = false }
                                    )
                                }
                                
                                Spacer(Modifier.width(10.dp))
                                Text(if (isWarmup) "Calentamiento" else "Serie ${index + 1}", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (prev != null && !isCardio) {
                                    Text("  ·  Anterior: ${fmtPrev(prev.weight)} × ${prev.repetitions}", color = TextSecondary, fontSize = 13.sp,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                } else Spacer(Modifier.weight(1f))
                                if (currentSets.size > 1) {
                                    IconButton(onClick = { currentSets.remove(set) }) {
                                        Icon(Icons.Default.RemoveCircleOutline, "Quitar serie", tint = AccentRed.copy(alpha = 0.7f))
                                    }
                                }
                            }  // fin línea superior
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {

                                if (isCardio) {
                                    // Diseño para Cardio: Temporizador
                                    CardioTimerView(
                                        timeSeconds = set.timeSeconds,
                                        onTimeChange = { newTime ->
                                            val idx = currentSets.indexOf(set)
                                            if (idx != -1) {
                                                currentSets[idx] = currentSets[idx].copy(timeSeconds = newTime)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    // Diseño para Pesas: KG y REPS
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        var weightText by remember(set.id) { mutableStateOf(if (set.weight == 0.0) "" else fmtWeightInput(set.weight)) }
                                        OutlinedTextField(
                                            value = weightText,
                                            onValueChange = { 
                                                weightText = it
                                                set.weight = it.replace(',', '.').toDoubleOrNull() ?: 0.0
                                                viewModel.saveDraftSoon()
                                            },
                                            label = { Text("Kg", fontSize = 11.sp) },
                                            modifier = Modifier.weight(1.1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentCyan,
                                                unfocusedBorderColor = BorderStrong,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary
                                            )
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        var repsText by remember(set.id) { mutableStateOf(if(set.repetitions == 0) "" else set.repetitions.toString()) }
                                        OutlinedTextField(
                                            value = repsText,
                                            onValueChange = { 
                                                repsText = it
                                                set.repetitions = it.toIntOrNull() ?: 0
                                                viewModel.saveDraftSoon()
                                            },
                                            label = { Text("Reps", fontSize = 11.sp) },
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentCyan,
                                                unfocusedBorderColor = BorderStrong,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary
                                            )
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        // RPE opcional (1-10): esfuerzo percibido de la serie
                                        var rpeText by remember(set.id) { mutableStateOf(set.rpe?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
                                        OutlinedTextField(
                                            value = rpeText,
                                            onValueChange = {
                                                rpeText = it
                                                set.rpe = it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 1.0..10.0 }
                                            },
                                            label = { Text("RPE", fontSize = 10.sp) },
                                            modifier = Modifier.width(60.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentCyan,
                                                unfocusedBorderColor = BorderStrong,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary
                                            )
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                FilledIconToggleButton(
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                                        containerColor = SurfaceAlt, contentColor = TextSecondary,
                                        checkedContainerColor = AccentTeal, checkedContentColor = OnAccent
                                    ),
                                    checked = set.isCompleted,
                                    onCheckedChange = { isChecked ->
                                        val idx = currentSets.indexOf(set)
                                        if (idx != -1) {
                                            currentSets[idx] = currentSets[idx].copy(isCompleted = isChecked)
                                            if (isChecked) {
                                                playDingSound()
                                                val w = currentSets[idx].weight
                                                val r = currentSets[idx].repetitions
                                                if (ex != null && viewModel.checkPersonalRecord(ex.exerciseName, w, r)) {
                                                    viewModel.newPersonalRecord = ex.exerciseName
                                                }
                                                // Descanso automático: uno nuevo sustituye al anterior; se ve en la barra superior
                                                if (viewModel.autoRestTimer) viewModel.startRest()
                                            }
                                        }
                                    },
                                    content = {
                                        Icon(Icons.Default.Check, if (set.isCompleted) "Desmarcar serie" else "Marcar serie como hecha", modifier = Modifier.size(26.dp))
                                    }
                                )

                            }
                                }  // fin Column de la serie
                        }
                    }

                    item {
                        // ── Banner de Nuevo PR ──────────────────────────────
                        val pr = viewModel.newPersonalRecord
                        if (pr != null) {
                            val defPr = ExerciseRepository.getCached().find { it.name == pr }
                            val prName = ExerciseNamesEs.display(pr)
                            LaunchedEffect(pr) { delay(3000); viewModel.newPersonalRecord = null }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceAlt)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🏆", fontSize = 22.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("NUEVO RÉCORD PERSONAL", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                        Text(prName, color = AccentWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        val prevStr = stringResource(R.string.workout_previous)
                        val nextStr = stringResource(R.string.workout_next)
                        val endStr  = stringResource(R.string.workout_end)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (currentIndex > 0) {
                                ModernButton(
                                    prevStr, 
                                    onClick = { viewModel.activeWorkoutExerciseIndex--; viewModel.saveDraftSoon() }, 
                                    modifier = Modifier.weight(1f), 
                                    containerColor = SurfaceDark, 
                                    contentColor = AccentWhite
                                )
                            }
                            ModernButton(
                                if (currentIndex < routine.exercises.size - 1) nextStr else endStr,
                                onClick = { 
                                    if (currentIndex < routine.exercises.size - 1) { viewModel.activeWorkoutExerciseIndex++; viewModel.saveDraftSoon() } else onFinish() 
                                },
                                modifier = Modifier.weight(1f),
                                containerColor = AccentCyan
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Temporizador de descanso — vive en el ViewModel para sobrevivir a la navegación
    if (showRestTimer) {
        RestTimerDialog(
            viewModel = viewModel,
            onDismiss = { showRestTimer = false }
        )
    }
}

@Composable
fun CardioTimerView(
    timeSeconds: Int,
    onTimeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isRunning by remember { mutableStateOf(false) }
    
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isRunning) {
                delay(1000L)
                onTimeChange(timeSeconds + 1)
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", timeSeconds / 60, timeSeconds % 60),
                color = AccentWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
            Text(stringResource(R.string.workout_time), color = TextSecondary, fontSize = 10.sp)
        }
        
        Spacer(Modifier.width(16.dp))
        
        IconButton(
            onClick = { isRunning = !isRunning },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isRunning) AccentRed.copy(alpha = 0.2f) else AccentCyan.copy(alpha = 0.2f)
            )
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isRunning) AccentRed else AccentCyan
            )
        }
        
        IconButton(onClick = { isRunning = false; onTimeChange(0) }) {
            Icon(Icons.Default.Refresh, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TEMPORIZADOR DE DESCANSO — la cuenta y el aviso viven en el ViewModel (RestTimer)
// ═══════════════════════════════════════════════════════════════════════════════
private fun fmtRest(seconds: Int) = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)

/** Indicador compacto del descanso para la barra superior; al pulsarlo abre los controles. */
@Composable
fun RestChip(viewModel: GymFlowViewModel, onClick: () -> Unit) {
    val status = viewModel.restStatus
    if (status == RestTimer.Status.IDLE) return
    val finished = status == RestTimer.Status.FINISHED
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (finished) AccentCyan else AccentCyan.copy(alpha = 0.14f),
        modifier = Modifier.semantics { contentDescription = if (finished) "Descanso terminado" else "Descanso: ${viewModel.restSecondsLeft} segundos" }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (status == RestTimer.Status.PAUSED) Icons.Default.Pause else Icons.Default.Timer, null,
                tint = if (finished) OnAccent else AccentCyan, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (finished) "¡Listo!" else fmtRest(viewModel.restSecondsLeft),
                color = if (finished) OnAccent else AccentCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }
    }
}

@Composable
fun RestTimerDialog(viewModel: GymFlowViewModel, onDismiss: () -> Unit) {
    val status = viewModel.restStatus
    val timeLeft = viewModel.restSecondsLeft
    val running = status == RestTimer.Status.RUNNING
    val finished = status == RestTimer.Status.FINISHED

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.rest_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (finished) "¡Descanso terminado!" else fmtRest(timeLeft),
                    color = if (running && timeLeft <= 5) AccentRed else AccentCyan,
                    fontSize = if (finished) 26.sp else 56.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Text("Duración: ${fmtRest(viewModel.defaultRestSeconds)}", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))

                if (status == RestTimer.Status.IDLE || finished) {
                    Button(
                        onClick = { viewModel.startRest() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = OnAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Empezar descanso", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { viewModel.addRestSeconds(-15) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceAlt, contentColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("−15 s", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                        Spacer(Modifier.width(14.dp))
                        FilledIconButton(
                            onClick = { if (running) viewModel.pauseRest() else viewModel.resumeRest() },
                            modifier = Modifier.size(60.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentCyan, contentColor = OnAccent)
                        ) {
                            Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, if (running) "Pausar" else "Reanudar", modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        FilledTonalButton(
                            onClick = { viewModel.addRestSeconds(15) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceAlt, contentColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("+15 s", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.restartRest() }) {
                            Icon(Icons.Default.Refresh, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.rest_reset), color = TextSecondary)
                        }
                        TextButton(onClick = { viewModel.cancelRest() }) {
                            Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancelar", color = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rest_close), color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// RESUMEN POST-WORKOUT
// ═══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSummaryScreen(
    summary: WorkoutSummaryData,
    onDone: () -> Unit,
    onShare: (() -> Unit)? = null
) {
    val minutes = summary.durationSeconds / 60
    val seconds = summary.durationSeconds % 60

    // Mejor serie (mayor peso × reps)
    val bestSet = summary.exercises.flatMap { ex ->
        ex.sets.filter { it.isCompleted || it.weight > 0 }.map { set ->
            Triple(ex.exerciseName, set.weight, set.repetitions)
        }
    }.maxByOrNull { it.second * it.third }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Confetti overlay
            ConfettiAnimation()

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            item {
                Spacer(Modifier.height(16.dp))

                // Trofeo
                Text("🏆", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.summary_great_job),
                    color = AccentWhite,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    summary.routineName,
                    color = AccentCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(32.dp))

                // Estadísticas principales
                val durationLbl  = stringResource(R.string.summary_duration)
                val totalKgLbl   = stringResource(R.string.summary_total_kg)
                val setsLbl      = stringResource(R.string.summary_sets)
                val exercisesLbl = stringResource(R.string.summary_exercises)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStatCard(
                        emoji = "⏱",
                        value = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                        label = durationLbl,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        emoji = "🏋️",
                        value = "${summary.totalVolume}",
                        label = totalKgLbl,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStatCard(
                        emoji = "✅",
                        value = "${summary.totalSets}",
                        label = setsLbl,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        emoji = "💪",
                        value = "${summary.exercises.size}",
                        label = exercisesLbl,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Mejor serie
                if (bestSet != null && bestSet.second > 0) {
                    Spacer(Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceAlt),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔥", fontSize = 28.sp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(stringResource(R.string.summary_best_set), color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                val bestDefn = ExerciseRepository.getCached().find { it.name == bestSet.first }
                                Text(
                                    ExerciseNamesEs.display(bestSet.first),
                                    color = AccentWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    "${fmtKg(bestSet.second)} × ${bestSet.third} reps",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = TextPrimary.copy(alpha = 0.06f))
                Spacer(Modifier.height(20.dp))

                // Desglose por ejercicio
                Text(
                    stringResource(R.string.summary_breakdown),
                    color = TextSecondary,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            items(summary.exercises.size) { i ->
                val exercise = summary.exercises[i]
                val completedSets = exercise.sets.filter { it.isCompleted || it.weight > 0 }
                if (completedSets.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val defn = ExerciseRepository.getCached().find { it.name == exercise.exerciseName }
                            Text(ExerciseNamesEs.display(exercise.exerciseName), color = AccentWhite, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            completedSets.forEachIndexed { idx, set ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.summary_set_n, idx + 1), color = TextSecondary, fontSize = 13.sp)
                                    Text(
                                        "${fmtKg(set.weight)} × ${set.repetitions}",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            // Mostrar nota si existe
                            if (!exercise.notes.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.StickyNote2, null, tint = AccentCyan.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(exercise.notes!!, color = TextSecondary, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                var showShare by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showShare = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.share_button), fontWeight = FontWeight.Bold)
                    }
                    ModernButton(
                        text = stringResource(R.string.summary_back_home),
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        containerColor = AccentCyan
                    )
                }
                if (showShare) {
                    ShareWorkoutDialog(summary = summary, onDismiss = { showShare = false })
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        }
    }
}

@Composable
fun SummaryStatCard(
    emoji: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp).fillMaxWidth()
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = AccentWhite, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONFETTI ANIMATION
// ═══════════════════════════════════════════════════════════════════════════════
private data class ConfettiParticle(
    var x: Float, var y: Float,
    val speedX: Float, val speedY: Float,
    val color: Color, val size: Float,
    val rotation: Float
)

@Composable
fun ConfettiAnimation() {
    val confettiColors = listOf(
        AccentCyan, Success, Gold,
        AccentRed, Color(0xFFE040FB), AccentWhite
    )
    val particles = remember {
        List(80) {
            ConfettiParticle(
                x = (Math.random() * 1200).toFloat(),
                y = -(Math.random() * 800).toFloat(),
                speedX = ((Math.random() - 0.5) * 4).toFloat(),
                speedY = (2 + Math.random() * 5).toFloat(),
                color = confettiColors.random(),
                size = (4 + Math.random() * 8).toFloat(),
                rotation = (Math.random() * 360).toFloat()
            )
        }.toMutableList()
    }

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(180) { // ~3 seconds at 60fps
            delay(16)
            tick++
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { p ->
            p.x += p.speedX
            p.y += p.speedY
            val alpha = (1f - (p.y / size.height)).coerceIn(0f, 1f)
            drawCircle(
                color = p.color.copy(alpha = alpha),
                radius = p.size,
                center = Offset(p.x, p.y)
            )
        }
        if (tick > 0) { /* force recomposition */ }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SetType Picker Dialog
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SetTypePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val types = listOf(
        Triple(SetType.NORMAL,   stringResource(R.string.set_type_normal),   AccentCyan),
        Triple(SetType.WARMUP,   stringResource(R.string.set_type_warmup),   TextSecondary),
        Triple(SetType.DROP_SET, stringResource(R.string.set_type_dropset),  Color(0xFFFF6B35)),
        Triple(SetType.FAILURE,  stringResource(R.string.set_type_failure),  Color(0xFF9C27B0))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(R.string.set_picker_title),
                color = AccentWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (type, label, color) ->
                    val isSelected = current == type
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(type) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) color.copy(alpha = 0.15f) else SurfaceDark
                        ),
                        border = BorderStroke(1.dp, if (isSelected) color else Border)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp).background(color, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (type) {
                                        SetType.WARMUP   -> "C"
                                        SetType.DROP_SET -> "D"
                                        SetType.FAILURE  -> "F"
                                        else             -> "N"
                                    },
                                    color = OnAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                label,
                                color = if (isSelected) color else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 15.sp
                            )
                            if (isSelected) {
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close), color = TextSecondary)
            }
        }
    )
}
