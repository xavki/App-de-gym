package com.gymflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRoutineScreen(
    initialRoutine: WorkoutSession?,
    preselectedExercises: List<WorkoutExercise>? = null,
    onSave: (WorkoutSession) -> Unit,
    onBack: () -> Unit,
    onAddMore: () -> Unit,
    onExerciseClick: (WorkoutExercise) -> Unit,
    onInfoClick: (WorkoutExercise) -> Unit
) {
    var name by remember { mutableStateOf(initialRoutine?.name ?: "") }
    val exercises = remember { mutableStateListOf<WorkoutExercise>().apply { 
        if (initialRoutine != null) addAll(initialRoutine.exercises) 
        else if (preselectedExercises != null) addAll(preselectedExercises) 
    } }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(if (initialRoutine != null) "Editar Rutina" else "Nueva Rutina", color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) } },
                actions = {
                    TextButton(onClick = { 
                        onSave(WorkoutSession(id = initialRoutine?.id ?: UUID.randomUUID().toString(), name = name, exercises = exercises.toMutableList())) 
                    }) {
                        Text("GUARDAR", color = AccentCyan, fontWeight = FontWeight.Black)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nombre de la rutina") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedLabelColor = AccentCyan
                )
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EJERCICIOS", color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onAddMore) {
                    Icon(Icons.Default.Add, null, tint = AccentCyan)
                    Spacer(Modifier.width(4.dp))
                    Text("AÑADIR", color = AccentCyan, fontWeight = FontWeight.Bold)
                }
            }

            // Hint de drag & drop
            if (exercises.size > 1) {
                Text(
                    "Mantén pulsado ↕ para reordenar",
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(exercises, key = { _, ex -> ex.id }) { index, ex ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onExerciseClick(ex) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Row(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Botones de reordenar
                            Column {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val item = exercises.removeAt(index)
                                            exercises.add(index - 1, item)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp, null, 
                                        tint = if (index > 0) AccentCyan else Color.DarkGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < exercises.size - 1) {
                                            val item = exercises.removeAt(index)
                                            exercises.add(index + 1, item)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                    enabled = index < exercises.size - 1
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown, null, 
                                        tint = if (index < exercises.size - 1) AccentCyan else Color.DarkGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            Spacer(Modifier.width(4.dp))
                            
                            val defn = ExerciseRepository.getCached().find { it.name == ex.exerciseName }
                            Text(defn?.nameEs ?: ex.exerciseName, color = TextPrimary, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(
                                "${ex.sets.size}s",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            IconButton(onClick = { onInfoClick(ex) }) {
                                Icon(Icons.Outlined.Info, null, tint = TextSecondary)
                            }
                            IconButton(onClick = { exercises.remove(ex) }) {
                                Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineDetailScreen(
    routine: WorkoutSession,
    isAnyActive: Boolean,
    isActiveThis: Boolean,
    onBack: () -> Unit,
    onEditRoutine: () -> Unit,
    onExerciseClick: (WorkoutExercise) -> Unit,
    onStartWorkout: () -> Unit,
    onInfoClick: (WorkoutExercise) -> Unit,
    // ViewModel necesario para guardar la programación
    viewModel: GymFlowViewModel? = null
) {
    val context = LocalContext.current
    var showScheduleDialog by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(routine.name, color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) } },
                actions = {
                    // Botón Programar
                    IconButton(onClick = { showScheduleDialog = true }) {
                        Icon(Icons.Default.CalendarMonth, null, tint = AccentCyan)
                    }
                    IconButton(onClick = onEditRoutine) { Icon(Icons.Default.Edit, null, tint = AccentWhite) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("EJERCICIOS", color = TextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(routine.exercises) { ex ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onExerciseClick(ex) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val defn = ExerciseRepository.getCached().find { it.name == ex.exerciseName }
                            Text(defn?.nameEs ?: ex.exerciseName, color = TextPrimary, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            IconButton(onClick = { onInfoClick(ex) }) {
                                Icon(Icons.Outlined.Info, null, tint = TextSecondary)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ModernButton(
                text = if (isActiveThis) "CONTINUAR ENTRENAMIENTO" else "INICIAR ENTRENAMIENTO",
                onClick = onStartWorkout,
                modifier = Modifier.fillMaxWidth(),
                containerColor = AccentCyan,
                enabled = !isAnyActive || isActiveThis
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // Diálogo de programación
    if (showScheduleDialog) {
        ScheduleDialog(
            routines           = listOf(routine),
            preselectedRoutine = routine,
            initialDay         = todayMidnight(),
            onDismiss          = { showScheduleDialog = false },
            onConfirm          = { schedule ->
                viewModel?.saveSchedule(context, schedule)
                showScheduleDialog = false
            }
        )
    }
}
