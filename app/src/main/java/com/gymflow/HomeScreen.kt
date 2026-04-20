package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
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
                        label = "Calendario",
                        selected = currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    GlowNavItem(
                        icon = Icons.Default.FitnessCenter,
                        label = "Rutinas",
                        selected = currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    GlowNavItem(
                        icon = Icons.Default.Person,
                        label = "Perfil",
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
                    history   = viewModel.workoutHistory,
                    isLoading = viewModel.isLoadingHistory,
                    onLogout  = onLogout
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
                            Text(
                                "⚡ ACTIVO",
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
                            Text("DURACIÓN", color = Color.Black.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MIS RUTINAS", color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            // Botón plantillas
            TextButton(
                onClick = { showTemplates = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Plantillas", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Text("Crea tu primera rutina", color = TextSecondary, fontWeight = FontWeight.Bold)
                    Text("Pulsa + para empezar", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
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
                                    "${routine.exercises.size} ejercicios",
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
    history: List<WorkoutSession>,
    isLoading: Boolean,
    onLogout: () -> Unit
) {
    val user = FirebaseAuth.getInstance().currentUser
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    // Estadísticas rápidas
    val totalWorkouts = history.size
    val totalMinutes  = history.sumOf { it.durationSeconds } / 60
    val totalSets     = history.sumOf { session ->
        session.exercises.sumOf { ex -> ex.sets.count { it.isCompleted || it.weight > 0 || it.repetitions > 0 } }
    }

    // Diálogo de ajustes
    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onLanguageChanged = { /* se recrea la activity automáticamente */ }
        )
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
                    label    = "Entrenos",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon     = Icons.Default.Timer,
                    value    = "${totalMinutes}m",
                    label    = "Minutos",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon     = Icons.Default.Repeat,
                    value    = "$totalSets",
                    label    = "Series",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(24.dp))
        }

        // ── Historial ───────────────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "HISTORIAL",
                    color          = TextSecondary,
                    fontWeight     = FontWeight.Black,
                    fontSize       = 12.sp,
                    letterSpacing  = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${history.size} entrenamientos",
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
                        Text("Sin entrenamientos aún", color = TextSecondary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("¡Empieza tu primera rutina!", color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
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
                Text("Cerrar sesión", fontWeight = FontWeight.Bold)
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
                Text("Ajustes", color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column {
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(16.dp))

                Text(
                    "IDIOMA",
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
    val templates = listOf(
        Triple("Hipertrofia", "💪", "4×10 — Pecho, Espalda, Pierna, Hombros, Brazo"),
        Triple("Fuerza",      "🔱", "5×5  — Sentadilla, Banca, Peso Muerto, Press"),
        Triple("Definición",  "🔥", "Cardio + HIIT — Circuito funcional de cuerpo completo")
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
                Text("Plantillas", color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Text(
                    "Elige una plantilla para crear una rutina preconfigurada",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                templates.forEachIndexed { i, (name, emoji, desc) ->
                    val (bg, accent) = colors[i]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(name) },
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
                                Text(emoji, fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(desc, color = TextSecondary, fontSize = 12.sp)
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
                Text("Cerrar", color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private fun updateLocale(context: android.content.Context, languageCode: String) {
    val locale = java.util.Locale(languageCode)
    java.util.Locale.setDefault(locale)
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
    // Recrear la activity para aplicar el cambio
    (context as? android.app.Activity)?.recreate()
}
