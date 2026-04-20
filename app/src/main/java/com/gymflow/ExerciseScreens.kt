package com.gymflow

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseSelectionScreen(
    exercises: List<ExerciseDefinition>,
    isLoading: Boolean = false,
    alreadySelected: List<String>,
    onExercisesSelected: (List<String>) -> Unit,
    onBack: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(alreadySelected) } }
    var searchQuery by remember { mutableStateOf("") }
    val selectedFilters = remember { mutableStateListOf<String>() }
    var filtersExpanded by remember { mutableStateOf(false) }
    
    // Lista fija de categorías (traducidas al español)
    val categories = listOf(
        "Cardio", "Fuerza", "Abdominales", "Abductores", "Aductores", 
        "Antebrazo", "Bíceps", "Cuello", "Cuádriceps", "Dorsales", 
        "Espalda Baja", "Espalda Media", "Gemelos", "Glúteos", 
        "Hombros", "Isquiotibiales", "Pecho", "Trapecios", "Tríceps"
    )

    val filtered = exercises.filter { ex ->
        val matchesSearch = ex.name.contains(searchQuery, ignoreCase = true) ||
                (ex.nameEs?.contains(searchQuery, ignoreCase = true) == true)
        
        val matchesFilter = if (selectedFilters.isEmpty()) {
            true
        } else {
            // Mapeo manual para asegurar que los filtros funcionan con los datos de Firestore
            val exCategories = (ex.mainGroup.split(",") + ex.musclesUsed.split(","))
                .map { it.trim().lowercase() }
            
            selectedFilters.any { filter -> 
                val fLower = filter.lowercase()
                exCategories.any { it.contains(fLower) } || 
                (fLower == "fuerza" && exCategories.any { it.contains("strength") }) ||
                (fLower == "pecho" && exCategories.any { it.contains("chest") }) ||
                (fLower == "bíceps" && exCategories.any { it.contains("biceps") }) ||
                (fLower == "tríceps" && exCategories.any { it.contains("triceps") }) ||
                (fLower == "hombros" && exCategories.any { it.contains("shoulders") }) ||
                (fLower == "gemelos" && exCategories.any { it.contains("calves") }) ||
                (fLower == "glúteos" && exCategories.any { it.contains("glutes") }) ||
                (fLower == "dorsales" && exCategories.any { it.contains("lats") }) ||
                (fLower == "cuádriceps" && exCategories.any { it.contains("quads") }) ||
                (fLower == "isquiotibiales" && exCategories.any { it.contains("hamstrings") }) ||
                (fLower == "abdominales" && exCategories.any { it.contains("abs") }) ||
                (fLower == "abductores" && exCategories.any { it.contains("abductor") }) ||
                (fLower == "aductores" && exCategories.any { it.contains("adductor") }) ||
                (fLower == "antebrazo" && exCategories.any { it.contains("forearm") }) ||
                (fLower == "cuello" && exCategories.any { it.contains("neck") }) ||
                (fLower == "trapecios" && exCategories.any { it.contains("trap") }) ||
                (fLower == "espalda baja" && exCategories.any { it.contains("lower back") }) ||
                (fLower == "espalda media" && exCategories.any { it.contains("mid back") || it.contains("middle back") })
            }
        }
        
        matchesSearch && matchesFilter
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Añadir Ejercicios", color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { 
                    IconButton(onClick = onBack) { 
                        Icon(Icons.Default.Close, null, tint = AccentWhite) 
                    } 
                },
                actions = {
                    TextButton(onClick = { onExercisesSelected(selected.toList()) }) {
                        Text("LISTO", color = AccentCyan, fontWeight = FontWeight.ExtraBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar ejercicio...", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, null, tint = TextSecondary)
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color(0xFF2A2A2A),
                            cursorColor = AccentCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = Color(0xFF0A0A0A),
                            unfocusedContainerColor = Color(0xFF0A0A0A)
                        )
                    )
                    
                    Spacer(Modifier.width(8.dp))
                    
                    // Botón de filtro con badge
                    Box {
                        FilledTonalIconButton(
                            onClick = { filtersExpanded = !filtersExpanded },
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (filtersExpanded) AccentCyan.copy(alpha = 0.2f) else Color(0xFF1A1A1A)
                            )
                        ) {
                            Icon(
                                if (filtersExpanded) Icons.Default.FilterListOff else Icons.Outlined.FilterList, 
                                null, 
                                tint = if (selectedFilters.isNotEmpty() || filtersExpanded) AccentCyan else TextSecondary
                            )
                        }
                        // Badge con el número de filtros activos
                        if (selectedFilters.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(AccentCyan, CircleShape)
                                    .align(Alignment.TopEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${selectedFilters.size}",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

                // Panel de filtros expandible con animación
                AnimatedVisibility(
                    visible = filtersExpanded,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                        border = BorderStroke(1.dp, Color(0xFF1A1A1A))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Cabecera
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Tune, null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "FILTROS",
                                    color = AccentWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Spacer(Modifier.weight(1f))
                                if (selectedFilters.isNotEmpty()) {
                                    TextButton(
                                        onClick = { selectedFilters.clear() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Icon(Icons.Default.ClearAll, null, tint = AccentRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Limpiar", color = AccentRed.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // Grupo: Tipo de ejercicio
                            FilterGroup(
                                title = "💪 Tipo",
                                items = listOf("Cardio", "Fuerza"),
                                selectedFilters = selectedFilters
                            )

                            Spacer(Modifier.height(12.dp))

                            // Grupo: Tren superior
                            FilterGroup(
                                title = "🔝 Tren superior",
                                items = listOf("Pecho", "Hombros", "Bíceps", "Tríceps", "Antebrazo", "Dorsales", "Trapecios", "Espalda Baja", "Espalda Media"),
                                selectedFilters = selectedFilters
                            )

                            Spacer(Modifier.height(12.dp))

                            // Grupo: Tren inferior
                            FilterGroup(
                                title = "🦵 Tren inferior",
                                items = listOf("Cuádriceps", "Isquiotibiales", "Glúteos", "Gemelos", "Abductores", "Aductores"),
                                selectedFilters = selectedFilters
                            )

                            Spacer(Modifier.height(12.dp))

                            // Grupo: Core / Otro
                            FilterGroup(
                                title = "🎯 Core",
                                items = listOf("Abdominales", "Cuello"),
                                selectedFilters = selectedFilters
                            )
                        }
                    }
                }
            }
            
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    items(filtered) { ex ->
                        val isSel = selected.contains(ex.name)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    if (isSel) selected.remove(ex.name) else selected.add(ex.name) 
                                }
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSel) Color(0xFF152A2A) else SurfaceDark),
                            border = if (isSel) BorderStroke(1.dp, AccentCyan.copy(0.5f)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ExerciseImageSmall(
                                    imageUrl = ex.imageUrl,
                                    size = 52.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ex.nameEs ?: ex.name,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        translateMuscleGroup(ex.mainGroup),
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = { onInfoClick(ex.name) }) {
                                    Icon(Icons.Outlined.Info, null, tint = TextSecondary)
                                }
                                if (isSel) Icon(Icons.Default.CheckCircle, null, tint = AccentCyan)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exercise: WorkoutExercise, onBack: () -> Unit, onInfoClick: () -> Unit) {
    val sets = remember { mutableStateListOf<ExerciseSet>().apply { addAll(exercise.sets) } }
    val definition = ExerciseRepository.getCached().find { it.name == exercise.exerciseName }
    
    LaunchedEffect(sets.size) {
        exercise.sets.clear()
        exercise.sets.addAll(sets)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(exercise.exerciseName, color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { 
                    IconButton(onClick = onBack) { 
                        Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) 
                    } 
                },
                actions = {
                    IconButton(onClick = onInfoClick) {
                        Icon(Icons.Outlined.Info, null, tint = AccentWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 24.dp)) {
            item {
                ExerciseImageAnimated(
                    imageUrl = definition?.imageUrl,
                    gifUrl   = definition?.gifUrl,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                if (definition != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        definition.difficulty?.let { diff ->
                            InfoChip(label = diff, color = AccentCyan)
                        }
                        definition.equipment?.let { equip ->
                            InfoChip(label = equip, color = Color(0xFF8E8E93))
                        }
                        definition.subCategory?.let { sub ->
                            InfoChip(label = sub, color = Color(0xFF3A3A3C))
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "MÚSCULOS",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                definition.musclesUsed,
                                color = AccentCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "CÓMO HACERLO",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = definition.instructionsEs ?: definition.instructions,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                            lineHeight = 22.sp
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SERIES", color = AccentCyan, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { 
                            sets.add(ExerciseSet(id = UUID.randomUUID().toString())) 
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentCyan)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("AÑADIR", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            
            items(sets) { set ->
                val index = sets.indexOf(set)
                var weightText by remember(set.id) { mutableStateOf(if(set.weight == 0.0) "" else set.weight.toString()) }
                var repsText by remember(set.id) { mutableStateOf(if(set.repetitions == 0) "" else set.repetitions.toString()) }

                Card(
                    modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp).background(AccentCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${index + 1}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        
                        Spacer(Modifier.width(16.dp))
                        
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { 
                                weightText = it
                                set.weight = it.toDoubleOrNull() ?: 0.0 
                            },
                            label = { Text("KG", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = Color.DarkGray,
                                cursorColor = AccentCyan,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        
                        Spacer(Modifier.width(8.dp))
                        
                        OutlinedTextField(
                            value = repsText,
                            onValueChange = { 
                                repsText = it
                                set.repetitions = it.toIntOrNull() ?: 0 
                            },
                            label = { Text("REPS", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = Color.DarkGray,
                                cursorColor = AccentCyan,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        
                        IconButton(onClick = { 
                            if (sets.size > 1) sets.remove(set) 
                        }) {
                            Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseInfoScreen(exerciseName: String, onBack: () -> Unit) {
    val definition = ExerciseRepository.getCached().find { it.name == exerciseName }
    var history by remember { mutableStateOf<List<ExerciseHistoryEntry>>(emptyList()) }
    val userId = FirebaseAuth.getInstance().currentUser?.uid
    val db = FirebaseFirestore.getInstance()

    LaunchedEffect(exerciseName) {
        if (userId != null) {
            db.collection("users").document(userId)
                .collection("exercise_history")
                .whereEqualTo("exerciseName", exerciseName)
                .get()
                .addOnSuccessListener { snapshot ->
                    val entries = snapshot.toObjects(ExerciseHistoryEntry::class.java)
                    history = entries.sortedByDescending { it.date }
                }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Información", color = AccentWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 24.dp)) {
            item {
                ExerciseImageAnimated(
                    imageUrl = definition?.imageUrl,
                    gifUrl   = definition?.gifUrl,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                
                Text(exerciseName, color = AccentCyan, fontSize = 28.sp, fontWeight = FontWeight.Black)
                if (definition != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(definition.mainGroup, color = TextSecondary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    Text("INSTRUCCIONES", color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(definition.instructionsEs ?: definition.instructions, color = TextPrimary, lineHeight = 22.sp)
                }
                
                Spacer(Modifier.height(40.dp))
                Text("HISTORIAL PERSONAL", color = AccentWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                
                if (history.isEmpty()) {
                    Text("Aún no has completado este ejercicio en ninguna rutina.", color = TextSecondary)
                }
            }

            items(history) { entry ->
                val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                Card(
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateFormat.format(entry.date), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.CheckCircle, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        entry.sets.forEachIndexed { index, set ->
                            Text(
                                "Serie ${index + 1}: ${set.weight} KG x ${set.repetitions} reps",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Grupo de filtros con título y chips
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun FilterGroup(
    title: String,
    items: List<String>,
    selectedFilters: MutableList<String>
) {
    Column {
        Text(
            title,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { category ->
                val isSelected = selectedFilters.contains(category)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) selectedFilters.remove(category)
                        else selectedFilters.add(category)
                    },
                    label = {
                        Text(
                            category,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = AccentCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF151515),
                        labelColor = TextSecondary,
                        selectedContainerColor = AccentCyan.copy(alpha = 0.15f),
                        selectedLabelColor = AccentCyan
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Color(0xFF252525),
                        selectedBorderColor = AccentCyan.copy(alpha = 0.5f),
                        borderWidth = 1.dp,
                        selectedBorderWidth = 1.dp,
                        enabled = true,
                        selected = isSelected,
                        disabledBorderColor = Color.DarkGray.copy(alpha = 0.3f),
                        disabledSelectedBorderColor = AccentCyan.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Traducción de grupos musculares (Firestore → Español)
// ═══════════════════════════════════════════════════════════════════════════════
private val muscleGroupMap = mapOf(
    "chest" to "Pecho",
    "shoulders" to "Hombros",
    "biceps" to "Bíceps",
    "triceps" to "Tríceps",
    "forearms" to "Antebrazos",
    "forearm" to "Antebrazo",
    "lats" to "Dorsales",
    "traps" to "Trapecios",
    "lower back" to "Espalda Baja",
    "middle back" to "Espalda Media",
    "mid back" to "Espalda Media",
    "upper back" to "Espalda Alta",
    "quads" to "Cuádriceps",
    "quadriceps" to "Cuádriceps",
    "hamstrings" to "Isquiotibiales",
    "glutes" to "Glúteos",
    "calves" to "Gemelos",
    "abductors" to "Abductores",
    "adductors" to "Aductores",
    "abs" to "Abdominales",
    "abdominals" to "Abdominales",
    "neck" to "Cuello",
    "cardio" to "Cardio",
    "strength" to "Fuerza",
    "full body" to "Cuerpo Completo"
)

fun translateMuscleGroup(group: String): String {
    // Intenta traducir cada segmento separado por comas
    return group.split(",").joinToString(", ") { segment ->
        val trimmed = segment.trim()
        muscleGroupMap[trimmed.lowercase()] ?: trimmed.replaceFirstChar { it.uppercase() }
    }
}
