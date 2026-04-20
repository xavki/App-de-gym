package com.gymflow

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

    // Usamos un SnapshotStateList para que Compose detecte cambios en la lista (añadir/quitar)
    val currentSets = remember(ex?.id) { 
        mutableStateListOf<ExerciseSet>().apply { 
            ex?.sets?.let { addAll(it) } 
        } 
    }

    // Sincronizamos los cambios de vuelta al objeto original
    LaunchedEffect(currentSets.size) {
        ex?.let {
            it.sets.clear()
            it.sets.addAll(currentSets)
        }
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
                actions = { TextButton(onClick = onFinish) { Text("FINALIZAR", color = AccentCyan, fontWeight = FontWeight.Black) } },
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
                        "Ejercicio ${currentIndex + 1} de ${routine.exercises.size}",
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
                    trackColor = Color(0xFF1A2A2A)
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
                                definition?.nameEs ?: ex.exerciseName, 
                                color = TextPrimary, 
                                fontSize = 28.sp, 
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showRestTimer = true }) {
                                Icon(Icons.Default.Timer, null, tint = AccentCyan)
                            }
                            IconButton(onClick = { onInfoClick(ex) }) {
                                Icon(Icons.Outlined.Info, null, tint = AccentCyan)
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isCardio) "SESIONES" else "SERIES", color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { 
                                currentSets.add(ExerciseSet(id = UUID.randomUUID().toString())) 
                            }) {
                                Icon(Icons.Default.Add, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("AÑADIR", color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    items(items = currentSets, key = { it.id }) { set ->
                        val index = currentSets.indexOf(set)
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (set.isCompleted) Color(0xFF152A2A) else SurfaceDark
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(28.dp).background(if(set.isCompleted) AccentCyan else Color.DarkGray, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${index + 1}", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                                
                                Spacer(Modifier.width(12.dp))

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
                                        var weightText by remember(set.id) { mutableStateOf(if(set.weight == 0.0) "" else set.weight.toString()) }
                                        OutlinedTextField(
                                            value = weightText,
                                            onValueChange = { 
                                                weightText = it
                                                set.weight = it.toDoubleOrNull() ?: 0.0
                                            },
                                            label = { Text("KG", fontSize = 10.sp) },
                                            modifier = Modifier.width(80.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentCyan,
                                                unfocusedBorderColor = Color.DarkGray,
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
                                            },
                                            label = { Text("REPS", fontSize = 10.sp) },
                                            modifier = Modifier.width(80.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentCyan,
                                                unfocusedBorderColor = Color.DarkGray,
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary
                                            )
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                Checkbox(
                                    checked = set.isCompleted,
                                    onCheckedChange = { isChecked ->
                                        val idx = currentSets.indexOf(set)
                                        if (idx != -1) {
                                            currentSets[idx] = currentSets[idx].copy(isCompleted = isChecked)
                                            // 🔔 Sonido "ding" + check PR
                                            if (isChecked) {
                                                playDingSound()
                                                val w = currentSets[idx].weight
                                                val r = currentSets[idx].repetitions
                                                if (ex != null && viewModel.checkPersonalRecord(ex.exerciseName, w, r)) {
                                                    viewModel.newPersonalRecord = ex.exerciseName
                                                }
                                            }
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentCyan, uncheckedColor = Color.Gray)
                                )

                                if (currentSets.size > 1) {
                                    IconButton(onClick = { 
                                        currentSets.remove(set)
                                    }) {
                                        Icon(Icons.Default.RemoveCircleOutline, null, tint = AccentRed.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        // ── Banner de Nuevo PR ──────────────────────────────
                        val pr = viewModel.newPersonalRecord
                        if (pr != null) {
                            val defPr = ExerciseRepository.getCached().find { it.name == pr }
                            val prName = defPr?.nameEs ?: pr
                            LaunchedEffect(pr) { delay(3000); viewModel.newPersonalRecord = null }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A00))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🏆", fontSize = 22.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("NUEVO RÉCORD PERSONAL", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                        Text(prName, color = AccentWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        // ── Notas del ejercicio ─────────────────────────────
                        var noteText by remember(ex.id) { mutableStateOf(ex.notes ?: "") }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = {
                                noteText = it
                                ex.notes = it
                            },
                            placeholder = { Text("Añadir nota... (ej: subir peso)", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = false,
                            maxLines = 2,
                            leadingIcon = { Icon(Icons.Default.StickyNote2, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan.copy(alpha = 0.5f),
                                unfocusedBorderColor = Color(0xFF1A1A1A),
                                cursorColor = AccentCyan,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = Color(0xFF080808),
                                unfocusedContainerColor = Color(0xFF080808)
                            )
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (currentIndex > 0) {
                                ModernButton(
                                    "ANTERIOR", 
                                    onClick = { viewModel.activeWorkoutExerciseIndex-- }, 
                                    modifier = Modifier.weight(1f), 
                                    containerColor = SurfaceDark, 
                                    contentColor = AccentWhite
                                )
                            }
                            ModernButton(
                                if (currentIndex < routine.exercises.size - 1) "SIGUIENTE" else "TERMINAR",
                                onClick = { 
                                    if (currentIndex < routine.exercises.size - 1) viewModel.activeWorkoutExerciseIndex++ else onFinish() 
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
            Text("TIEMPO", color = TextSecondary, fontSize = 10.sp)
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
// TEMPORIZADOR DE DESCANSO — con vibración al llegar a 0
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun RestTimerDialog(viewModel: GymFlowViewModel, onDismiss: () -> Unit) {
    val context   = LocalContext.current
    val timeLeft  = viewModel.restTimerSeconds
    val isRunning = viewModel.restTimerRunning

    // Tick cada segundo
    LaunchedEffect(isRunning, timeLeft) {
        if (isRunning && timeLeft > 0) {
            delay(1000L)
            viewModel.restTimerSeconds--
        } else if (isRunning && timeLeft <= 0) {
            viewModel.restTimerRunning = false
            // 📳 Vibración al terminar descanso
            vibratePattern(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1A1A),
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Descanso", color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(Modifier.height(8.dp))

                // Display grande del timer
                Text(
                    text = String.format(Locale.getDefault(), "%02d:%02d", timeLeft / 60, timeLeft % 60),
                    color = if (timeLeft <= 5 && isRunning) AccentRed else AccentCyan,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(Modifier.height(20.dp))

                // Controles ±15s
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // -15s
                    FilledTonalButton(
                        onClick = { viewModel.restTimerSeconds = maxOf(0, timeLeft - 15) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF1A2A2A),
                            contentColor = AccentCyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("-15s", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(Modifier.width(16.dp))

                    // Botón Play/Pause grande
                    FilledIconButton(
                        onClick = { viewModel.restTimerRunning = !isRunning },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isRunning) AccentRed else AccentCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    // +15s
                    FilledTonalButton(
                        onClick = { viewModel.restTimerSeconds = timeLeft + 15 },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF1A2A2A),
                            contentColor = AccentCyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("+15s", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Botón reset rápido
                TextButton(onClick = {
                    viewModel.restTimerSeconds = 60
                    viewModel.restTimerRunning = false
                }) {
                    Icon(Icons.Default.Refresh, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reiniciar a 1:00", color = TextSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = TextSecondary, fontWeight = FontWeight.Bold)
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
    onDone: () -> Unit
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
                    "¡Buen trabajo!",
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStatCard(
                        emoji = "⏱",
                        value = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                        label = "Duración",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        emoji = "🏋️",
                        value = "${summary.totalVolume}",
                        label = "Kg totales",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStatCard(
                        emoji = "✅",
                        value = "${summary.totalSets}",
                        label = "Series",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatCard(
                        emoji = "💪",
                        value = "${summary.exercises.size}",
                        label = "Ejercicios",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Mejor serie
                if (bestSet != null && bestSet.second > 0) {
                    Spacer(Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF152A2A)),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔥", fontSize = 28.sp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("MEJOR SERIE", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                val bestDefn = ExerciseRepository.getCached().find { it.name == bestSet.first }
                                Text(
                                    bestDefn?.nameEs ?: bestSet.first,
                                    color = AccentWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    "${bestSet.second} kg × ${bestSet.third} reps",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(20.dp))

                // Desglose por ejercicio
                Text(
                    "DESGLOSE",
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
                            Text(defn?.nameEs ?: exercise.exerciseName, color = AccentWhite, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            completedSets.forEachIndexed { idx, set ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Serie ${idx + 1}", color = TextSecondary, fontSize = 13.sp)
                                    Text(
                                        "${set.weight} kg × ${set.repetitions}",
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
                ModernButton(
                    text = "VOLVER AL INICIO",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AccentCyan
                )
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A))
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
        AccentCyan, Color(0xFF4CAF50), Color(0xFFFFD700),
        Color(0xFFFF6B6B), Color(0xFFE040FB), AccentWhite
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
