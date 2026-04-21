package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: GymFlowViewModel,
    onCreateRoutine: () -> Unit,
    onRoutineClick: (WorkoutSession) -> Unit,
    onContinueWorkout: () -> Unit,
    onDeleteRoutine: (WorkoutSession) -> Unit,
    onCopyRoutine: (WorkoutSession) -> Unit,
    onLogout: () -> Unit
) {
    // 0 = Calendario, 1 = Rutinas, 2 = Perfil
    val pagerState  = rememberPagerState(initialPage = 1) { 3 }
    val scope       = rememberCoroutineScope()
    val currentPage = pagerState.currentPage

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlowNavItem(
                        icon = Icons.Default.CalendarMonth,
                        label = stringResource(R.string.nav_calendar),
                        selected = currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    GlowNavItem(
                        icon = Icons.Default.FitnessCenter,
                        label = stringResource(R.string.nav_routines),
                        selected = currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    GlowNavItem(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.nav_profile),
                        selected = currentPage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentPage == 1) {
                FloatingActionButton(onClick = onCreateRoutine, containerColor = AccentWhite) {
                    Icon(Icons.Default.Add, null)
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { page ->
            when (page) {
                0 -> CalendarScreen(
                    viewModel = viewModel,
                    routines  = viewModel.routines
                )
                1 -> RoutinesTab(
                    viewModel.routines,
                    viewModel.activeWorkout,
                    viewModel.totalSeconds,
                    viewModel.isLoadingRoutines,
                    onRoutineClick,
                    onContinueWorkout,
                    onDeleteRoutine,
                    onCopyRoutine,
                    onCreateFromTemplate = { name -> viewModel.createFromTemplate(name) }
                )
                2 -> ProfileScreen(
                    viewModel = viewModel,
                    history   = viewModel.workoutHistory,
                    isLoading = viewModel.isLoadingHistory,
                    onLogout  = onLogout,
                    onNavigateToPRBoard = { /* handled via onNavigate */ }
                )
            }
        }
    }
}

@Composable
fun RoutinesTab(
    routines: List<WorkoutSession>,
    activeWorkout: WorkoutSession?,
    totalSeconds: Int,
    isLoading: Boolean,
    onRoutineClick: (WorkoutSession) -> Unit,
    onContinueWorkout: () -> Unit,
    onDeleteRoutine: (WorkoutSession) -> Unit,
    onCopyRoutine: (WorkoutSession) -> Unit,
    onCreateFromTemplate: (String) -> Unit
) {
    var showTemplates by remember { mutableStateOf(false) }
    if (showTemplates) {
        TemplatesDialog(
            onDismiss = { showTemplates = false },
            onSelect   = { name -> onCreateFromTemplate(name); showTemplates = false }
        )
    }
    // Animación de pulso para la tarjeta activa
    val infiniteTransition = rememberInfiniteTransition(label = "active")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(40.dp))

        // Título con acento
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(AccentCyan, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Text("GymFlow", color = AccentWhite, fontSize = 42.sp, fontWeight = FontWeight.Black)
        }
        
        if (activeWorkout != null) {
            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onContinueWorkout() },
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    AccentCyan.copy(alpha = pulseAlpha),
                                    Color(0xFF1B8A80).copy(alpha = pulseAlpha)
                                )
                            ),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.routines_active),
                                color = Color.Black.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(activeWorkout.name, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                String.format("%02d:%02d", (totalSeconds % 3600) / 60, totalSeconds % 60),
                                fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black
                            )
                            Text(stringResource(R.string.routines_duration), color = Color.Black.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.routines_my_routines), color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            // Botón plantillas
            TextButton(
                onClick = { showTemplates = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.routines_templates), color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("${routines.size}", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (routines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FitnessCenter, null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.routines_empty_title), color = TextSecondary, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.routines_empty_subtitle), color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(routines) { routine ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onRoutineClick(routine) },
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(modifier = Modifier.padding(0.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Barra lateral de acento
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(64.dp)
                                    .background(
                                        Brush.verticalGradient(listOf(AccentCyan, AccentCyan.copy(alpha = 0.2f))),
                                        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
                                    )
                            )
                            
                            Spacer(Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
                                Text(routine.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.routines_exercises_count, routine.exercises.size),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            
                            IconButton(onClick = { onCopyRoutine(routine) }) {
                                Icon(Icons.Default.ContentCopy, null, tint = AccentCyan.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onDeleteRoutine(routine) }) {
                                Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                            }
                            
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// PERFIL — Rediseño profesional
// ════════════════════════════════════════════════════════════════════════════════
@Composable
fun ProfileScreen(
    viewModel: GymFlowViewModel,
    history: List<WorkoutSession>,
    isLoading: Boolean,
    onLogout: () -> Unit,
    onNavigateToPRBoard: (() -> Unit)? = null
) {
    val user = FirebaseAuth.getInstance().currentUser
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showBodyMeasurements by remember { mutableStateOf(false) }
    var showPRBoard by remember { mutableStateOf(false) }

    // Estadísticas rápidas
    val totalWorkouts = history.size
    val totalMinutes  = history.sumOf { it.durationSeconds } / 60
    val totalSets     = history.sumOf { session ->
        session.exercises.sumOf { ex -> ex.sets.count { it.isCompleted || it.weight > 0 || it.repetitions > 0 } }
    }

    // Diálogo de ajustes
    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onLanguageChanged = { }
        )
    }

    // Pantalla de medidas corporales (overlay)
    if (showBodyMeasurements) {
        BodyMeasurementsScreen(
            viewModel = viewModel,
            onBack = { showBodyMeasurements = false }
        )
        return
    }

    // PR Board (overlay)
    if (showPRBoard) {
        PRBoardScreen(
            viewModel = viewModel,
            onBack = { showPRBoard = false }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Cabecera ────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(40.dp))

            // Avatar + nombre + settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(AccentCyan, Color(0xFF1B8A80)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val initials = (user?.displayName ?: "U")
                        .split(" ")
                        .take(2)
                        .joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
                    Text(
                        initials,
                        color      = Color.Black,
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: "Usuario",
                        color      = AccentWhite,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        user?.email ?: "",
                        color    = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                // Botón de ajustes
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Ajustes",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }

        // ── Cards de estadísticas ───────────────────────────────────────────
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    icon     = Icons.Default.FitnessCenter,
                    value    = "$totalWorkouts",
                    label    = stringResource(R.string.profile_workouts),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon     = Icons.Default.Timer,
                    value    = "${totalMinutes}m",
                    label    = stringResource(R.string.profile_minutes),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon     = Icons.Default.Repeat,
                    value    = "$totalSets",
                    label    = stringResource(R.string.profile_total_sets),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Botón PR Board
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A0A00)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { showPRBoard = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🏆", fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.pr_board_title), color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Tus mejores levantamientos", color = TextSecondary, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(24.dp))
        }

        // ── Gráfica de progreso ──────────────────────────────────────────────
        item {
            WorkoutProgressChart(history = history)
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(24.dp))
        }

        // ── Racha + Botón Medidas ───────────────────────────────────────────
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Card Racha
                val streak = viewModel.workoutStreak
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A08)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔥", fontSize = 22.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (streak > 0) "$streak"
                            else "0",
                            color = if (streak > 0) Color(0xFFFFD700) else TextSecondary,
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp
                        )
                        Text(
                            stringResource(R.string.streak_title),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                // Botón Medidas corporales
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).clickable { showBodyMeasurements = true }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⚖️", fontSize = 22.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            viewModel.bodyMeasurements.lastOrNull()?.let { "${it.weight} kg" } ?: "—",
                            color = AccentCyan,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        )
                        Text(
                            stringResource(R.string.body_menu_item),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Mapa muscular ────────────────────────────────────────────────────
        item {
            MuscleMapCard(history = history)
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(24.dp))
        }

        // ── Logros ───────────────────────────────────────────────────────────
        item {
            Text(
                stringResource(R.string.achievements_title),
                color = TextSecondary,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(12.dp))
            // Grid 3 columnas
            val cols = 3
            val achRows = viewModel.achievements.chunked(cols)
            achRows.forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { ach ->
                        val unlocked = ach.unlockedAt != null
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (unlocked) Color(0xFF0D1A1A) else Color(0xFF0A0A0A)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            border = if (unlocked) BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f)) else null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    ach.emoji,
                                    fontSize = 22.sp,
                                    modifier = if (!unlocked) Modifier.alpha(0.25f) else Modifier
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (unlocked) ach.titleEs else stringResource(R.string.achievement_locked),
                                    color = if (unlocked) AccentWhite else TextSecondary.copy(alpha = 0.4f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                    // Relleno si la última fila tiene menos de 3
                    repeat(cols - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(24.dp))
        }

        // ── Historial ───────────────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.profile_history),
                    color          = TextSecondary,
                    fontWeight     = FontWeight.Black,
                    fontSize       = 12.sp,
                    letterSpacing  = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.profile_history_count, history.size),
                    color    = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        if (isLoading) {
            item {
                Box(
                    modifier         = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            }
        } else if (history.isEmpty()) {
            item {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape    = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.padding(40.dp).fillMaxWidth()
                    ) {
                        Icon(Icons.Default.HistoryToggleOff, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.profile_no_workouts), color = TextSecondary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.profile_start_first), color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(history) { session ->
                WorkoutHistoryCard(session)
                Spacer(Modifier.height(12.dp))
            }
        }

        // ── Botón cerrar sesión ─────────────────────────────────────────────
        item {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick  = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                border   = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f)),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
            ) {
                Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_logout), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
        shape    = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Icon(icon, null, tint = AccentCyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, color = AccentWhite, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
fun WorkoutHistoryCard(session: WorkoutSession) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val minutes = session.durationSeconds / 60
    val seconds = session.durationSeconds % 60
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.name, color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format("%02d:%02d", minutes, seconds), 
                    color = AccentCyan, 
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarToday, null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(dateFormat.format(session.date), color = TextSecondary, fontSize = 12.sp)
            }
            
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f), thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            session.exercises.forEach { exercise ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        exercise.exerciseName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    exercise.sets.filter { it.isCompleted }.forEachIndexed { index, set ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("S${index + 1}", color = TextSecondary, fontSize = 12.sp)
                            Text("${set.weight} kg x ${set.repetitions}", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                    if (exercise.sets.none { it.isCompleted }) {
                        exercise.sets.forEachIndexed { index, set ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("S${index + 1}", color = TextSecondary, fontSize = 12.sp)
                                Text("${set.weight} kg x ${set.repetitions}", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Gráfica de progreso del historial
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun WorkoutProgressChart(history: List<WorkoutSession>) {
    // Tabs: 0 = Entrenamientos por semana, 1 = Volumen total
    var selectedTab by remember { mutableIntStateOf(0) }
    val weeksTab  = stringResource(R.string.chart_weeks_tab)
    val volumeTab = stringResource(R.string.chart_volume_tab)
    val tabs = listOf(weeksTab, volumeTab)

    // ── Datos: últimas 8 semanas ────────────────────────────────────────────
    val calendar = Calendar.getInstance()
    val weekLabels = remember {
        (7 downTo 0).map { weeksAgo ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.WEEK_OF_YEAR, -weeksAgo)
            val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
            sdf.format(cal.time)
        }
    }

    // Cuenta de entrenos por semana
    val countByWeek = remember(history) {
        val result = IntArray(8)
        history.forEach { session ->
            val sessionCal = Calendar.getInstance().apply { time = session.date }
            val todayCal   = Calendar.getInstance()
            val diffMs = todayCal.timeInMillis - sessionCal.timeInMillis
            val diffWeeks = (diffMs / (7L * 24 * 60 * 60 * 1000)).toInt()
            if (diffWeeks in 0..7) result[7 - diffWeeks]++
        }
        result.toList()
    }

    // Volumen total por semana (sum peso * reps)
    val volumeByWeek = remember(history) {
        val result = FloatArray(8)
        history.forEach { session ->
            val sessionCal = Calendar.getInstance().apply { time = session.date }
            val todayCal   = Calendar.getInstance()
            val diffMs = todayCal.timeInMillis - sessionCal.timeInMillis
            val diffWeeks = (diffMs / (7L * 24 * 60 * 60 * 1000)).toInt()
            if (diffWeeks in 0..7) {
                val vol = session.exercises.sumOf { ex ->
                    ex.sets.sumOf { set -> (set.weight * set.repetitions).toDouble() }
                }.toFloat()
                result[7 - diffWeeks] += vol
            }
        }
        result.toList()
    }

    val hasData = history.isNotEmpty()

    Column {
        // Título de sección
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.BarChart,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.profile_progress),
                color         = TextSecondary,
                fontWeight    = FontWeight.Black,
                fontSize      = 12.sp,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(14.dp))

        // Card contenedora
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A1A)),
            shape  = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // Tab selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A1010), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    tabs.forEachIndexed { index, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selectedTab == index) AccentCyan.copy(alpha = 0.2f)
                                    else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color      = if (selectedTab == index) AccentCyan else TextSecondary,
                                fontSize   = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (!hasData) {
                    Box(
                        modifier         = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.BarChart,
                                null,
                                tint     = TextSecondary.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.chart_no_data), color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                    }
                } else {
                    if (selectedTab == 0) {
                        BarChartWeekly(
                            values = countByWeek,
                            labels = weekLabels
                        )
                    } else {
                        LineChartVolume(
                            values = volumeByWeek,
                            labels = weekLabels
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BarChartWeekly(values: List<Int>, labels: List<String>) {
    val maxVal = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    val barColor = AccentCyan
    val density  = LocalDensity.current

    // Animación de entrada
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }
    val progress by animProgress.asState()

    // Tooltip: índice seleccionado
    var selectedIndex by remember { mutableStateOf(-1) }

    val labelTextPaint = remember {
        android.graphics.Paint().apply {
            color     = TextSecondary.copy(alpha = 0.7f).toArgb()
            textSize  = with(density) { 9.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val valuePaint = remember {
        android.graphics.Paint().apply {
            color     = AccentWhite.toArgb()
            textSize  = with(density) { 10.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(values) {
                detectTapGestures { offset ->
                    val barWidth = size.width / values.size.toFloat()
                    val idx = (offset.x / barWidth).toInt().coerceIn(0, values.size - 1)
                    selectedIndex = if (selectedIndex == idx) -1 else idx
                }
            }
    ) {
        val chartHeight = size.height - 28.dp.toPx() // space for labels
        val barSlot     = size.width / values.size
        val barW        = barSlot * 0.55f
        val cornerR     = 6.dp.toPx()

        values.forEachIndexed { i, value ->
            val fraction = (value / maxVal) * progress
            val barH     = (chartHeight * fraction).coerceAtLeast(if (value > 0) cornerR * 2 else 0f)
            val left     = i * barSlot + (barSlot - barW) / 2
            val top      = chartHeight - barH

            // Fondo de barra (guía)
            drawRoundRect(
                color        = Color.White.copy(alpha = 0.04f),
                topLeft      = Offset(left, 0f),
                size         = Size(barW, chartHeight),
                cornerRadius = CornerRadius(cornerR)
            )

            if (barH > 0f) {
                val isSelected = (i == selectedIndex)
                // Barra con gradiente
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            if (isSelected) AccentCyan else AccentCyan.copy(alpha = 0.85f),
                            AccentCyan.copy(alpha = 0.3f)
                        ),
                        startY = top,
                        endY   = chartHeight
                    ),
                    topLeft      = Offset(left, top),
                    size         = Size(barW, barH),
                    cornerRadius = CornerRadius(cornerR)
                )

                // Valor encima de la barra (solo si seleccionada o todas pequeñas)
                if (isSelected || values.size <= 4) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "$value",
                        left + barW / 2,
                        top - 6.dp.toPx(),
                        valuePaint
                    )
                }
            }

            // Label fecha debajo
            drawContext.canvas.nativeCanvas.drawText(
                labels[i],
                left + barW / 2,
                size.height,
                labelTextPaint
            )
        }

        // Línea base
        drawLine(
            color       = Color.White.copy(alpha = 0.08f),
            start       = Offset(0f, chartHeight),
            end         = Offset(size.width, chartHeight),
            strokeWidth = 1.dp.toPx()
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.chart_tap_hint),
        color    = TextSecondary.copy(alpha = 0.4f),
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
fun LineChartVolume(values: List<Float>, labels: List<String>) {
    val maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val density = LocalDensity.current

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
    }
    val progress by animProgress.asState()

    var selectedIndex by remember { mutableStateOf(-1) }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color     = TextSecondary.copy(alpha = 0.7f).toArgb()
            textSize  = with(density) { 9.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val tooltipPaint = remember {
        android.graphics.Paint().apply {
            color     = AccentWhite.toArgb()
            textSize  = with(density) { 10.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(values) {
                detectTapGestures { offset ->
                    val slotW = size.width / values.size.toFloat()
                    val idx = (offset.x / slotW).toInt().coerceIn(0, values.size - 1)
                    selectedIndex = if (selectedIndex == idx) -1 else idx
                }
            }
    ) {
        val chartH   = size.height - 28.dp.toPx()
        val slotW    = size.width / (values.size - 1).coerceAtLeast(1).toFloat()
        val dotR     = 5.dp.toPx()
        val lineW    = 2.5.dp.toPx()

        fun xOf(i: Int) = if (values.size == 1) size.width / 2 else i * slotW
        fun yOf(v: Float) = chartH - (v / maxVal * chartH * progress)

        // Área bajo la curva
        val fillPath = Path().apply {
            val first = values.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
            moveTo(xOf(first), chartH)
            values.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
            lineTo(xOf(values.lastIndex), chartH)
            close()
        }
        drawPath(
            path  = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(AccentCyan.copy(alpha = 0.22f), Color.Transparent),
                startY = 0f,
                endY   = chartH
            )
        )

        // Línea principal
        val linePath = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) linePath.moveTo(xOf(i), yOf(v))
            else        linePath.lineTo(xOf(i), yOf(v))
        }
        drawPath(
            path   = linePath,
            brush  = Brush.horizontalGradient(listOf(AccentCyan.copy(alpha = 0.5f), AccentCyan)),
            style  = Stroke(
                width     = lineW,
                cap       = StrokeCap.Round,
                join      = StrokeJoin.Round
            )
        )

        // Línea guía horizontal (promedio)
        val avg = values.filter { it > 0 }.average().toFloat()
        if (avg > 0f) {
            val yAvg = yOf(avg)
            drawLine(
                color       = AccentCyan.copy(alpha = 0.15f),
                start       = Offset(0f, yAvg),
                end         = Offset(size.width, yAvg),
                strokeWidth = 1.dp.toPx(),
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
        }

        // Puntos y labels
        values.forEachIndexed { i, v ->
            val cx = xOf(i)
            val cy = yOf(v)
            val isSelected = (i == selectedIndex)

            if (v > 0f) {
                // Halo en seleccionado
                if (isSelected) {
                    drawCircle(
                        color  = AccentCyan.copy(alpha = 0.25f),
                        radius = dotR * 2.8f,
                        center = Offset(cx, cy)
                    )
                }
                // Punto relleno
                drawCircle(
                    color  = AccentCyan,
                    radius = if (isSelected) dotR * 1.5f else dotR,
                    center = Offset(cx, cy)
                )
                // Punto interior
                drawCircle(
                    color  = Color(0xFF0D1A1A),
                    radius = if (isSelected) dotR * 0.7f else dotR * 0.5f,
                    center = Offset(cx, cy)
                )

                // Tooltip valor
                if (isSelected) {
                    val kg = if (v >= 1000f) "${(v / 1000).toInt()}t" else "${v.toInt()} kg"
                    drawContext.canvas.nativeCanvas.drawText(
                        kg,
                        cx,
                        cy - 14.dp.toPx(),
                        tooltipPaint
                    )
                }
            }

            // Label fecha
            drawContext.canvas.nativeCanvas.drawText(
                labels[i],
                cx,
                size.height,
                labelPaint
            )
        }

        // Línea base
        drawLine(
            color       = Color.White.copy(alpha = 0.08f),
            start       = Offset(0f, chartH),
            end         = Offset(size.width, chartH),
            strokeWidth = 1.dp.toPx()
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.chart_volume_hint),
        color    = TextSecondary.copy(alpha = 0.4f),
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// Bottom Nav Item con glow
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun GlowNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glow detrás del icono seleccionado
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .blur(16.dp)
                        .background(AccentCyan.copy(alpha = 0.4f), CircleShape)
                )
            }
            Icon(
                icon, null,
                tint = if (selected) AccentCyan else TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) AccentCyan else TextSecondary.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        // Punto indicador
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(AccentCyan, CircleShape)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Diálogo de ajustes (idioma)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsDialog(
    viewModel: GymFlowViewModel,
    onDismiss: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("GymFlowPrefs", android.content.Context.MODE_PRIVATE)
    val currentLang = prefs.getString("app_language", "es") ?: "es"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_title), color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column {
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(16.dp))

                Text(
                    stringResource(R.string.settings_language),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(12.dp))

                // Español
                LanguageOption(
                    flag = "🇪🇸",
                    name = "Español",
                    isSelected = currentLang == "es",
                    onClick = {
                        if (currentLang != "es") {
                            prefs.edit().putString("app_language", "es").apply()
                            updateLocale(context, "es")
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                // English
                LanguageOption(
                    flag = "🇬🇧",
                    name = "English",
                    isSelected = currentLang == "en",
                    onClick = {
                        if (currentLang != "en") {
                            prefs.edit().putString("app_language", "en").apply()
                            updateLocale(context, "en")
                        }
                    }
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(16.dp))

                // ── SECCIÓN: Timer de descanso automático ───────────────────
                Text(
                    "⏱️ ENTRENAMIENTO",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Timer automático", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Iniciar descanso al completar serie", color = TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = viewModel.autoRestTimer,
                                onCheckedChange = { viewModel.autoRestTimer = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = AccentCyan
                                )
                            )
                        }
                        if (viewModel.autoRestTimer) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                            Spacer(Modifier.height(12.dp))
                            Text("Duración: ${viewModel.defaultRestSeconds}s", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Slider(
                                value = viewModel.defaultRestSeconds.toFloat(),
                                onValueChange = { viewModel.defaultRestSeconds = it.toInt() },
                                valueRange = 30f..300f,
                                steps = 9,
                                colors = SliderDefaults.colors(activeTrackColor = AccentCyan, thumbColor = AccentCyan)
                            )
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("30s", color = TextSecondary, fontSize = 10.sp)
                                Text("5min", color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close), color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun LanguageOption(
    flag: String,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentCyan.copy(alpha = 0.12f) else Color(0xFF0A0A0A)
        ),
        border = if (isSelected) BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)) else BorderStroke(1.dp, Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(flag, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Text(
                name,
                color = if (isSelected) AccentCyan else TextPrimary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp
            )
            Spacer(Modifier.weight(1f))
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Diálogo de plantillas de entrenamiento
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun TemplatesDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    // Triple: (internalId, emoji, displayName, description)
    data class TemplateItem(val id: String, val emoji: String, val displayName: String, val desc: String)
    val templates = listOf(
        TemplateItem("Hipertrofia", "💪", stringResource(R.string.template_hypertrophy),  stringResource(R.string.template_hypertrophy_desc)),
        TemplateItem("Fuerza",      "🔱", stringResource(R.string.template_strength),     stringResource(R.string.template_strength_desc)),
        TemplateItem("Definición",  "🔥", stringResource(R.string.template_definition),   stringResource(R.string.template_definition_desc))
    )
    val colors = listOf(
        Color(0xFF1A2A3A) to AccentCyan,
        Color(0xFF2A1A1A) to Color(0xFFFF6B6B),
        Color(0xFF2A2A0A) to Color(0xFFFFD700)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfaceDark,
        shape            = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.templates_title), color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Text(
                    stringResource(R.string.templates_subtitle),
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                templates.forEachIndexed { i, item ->
                    val (bg, accent) = colors[i]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(item.id) },
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = bg),
                        border   = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(accent.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(item.emoji, fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.displayName, color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(item.desc, color = TextSecondary, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = accent.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close), color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private fun updateLocale(context: android.content.Context, languageCode: String) {
    // 1. Guardar preferencia
    context.getSharedPreferences("GymFlowPrefs", android.content.Context.MODE_PRIVATE)
        .edit().putString("app_language", languageCode).apply()
    // 2. Aplicar locale
    val locale = java.util.Locale(languageCode)
    java.util.Locale.setDefault(locale)
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    // 3. Recrear la Activity para que Android cargue los strings correctos
    (context as? android.app.Activity)?.recreate()
}
