package com.gymflow

import android.widget.DatePicker
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.*

// ════════════════════════════════════════════════════════════════════════════════
// PANTALLA PRINCIPAL DEL CALENDARIO
// ════════════════════════════════════════════════════════════════════════════════
@Composable
fun CalendarScreen(
    viewModel: GymFlowViewModel,
    routines: List<WorkoutSession>
) {
    val context = LocalContext.current
    var selectedDay        by remember { mutableStateOf(todayMidnight()) }
    var showDialog         by remember { mutableStateOf(false) }
    var preselectedRoutine by remember { mutableStateOf<WorkoutSession?>(null) }

    // Días que tienen al menos una rutina programada (para el grid)
    val schedulesByDay = remember(viewModel.scheduledRoutines.toList()) {
        buildScheduleMap(viewModel.scheduledRoutines)
    }

    // Rutinas programadas para el día seleccionado
    val daySchedules = remember(selectedDay, viewModel.scheduledRoutines.toList()) {
        viewModel.scheduledRoutines.filter { s ->
            isSameDay(s.startDate, selectedDay) || isRecurringOnDay(s, selectedDay)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(40.dp))
                Text("Calendario", color = AccentWhite, fontSize = 42.sp, fontWeight = FontWeight.Black)
                Text("Planifica tus entrenamientos", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
            }

            // ── Calendario mensual ────────────────────────────────────────────
            MonthlyCalendar(
                selectedDay   = selectedDay,
                scheduledDays = schedulesByDay.keys,
                onDaySelected = { selectedDay = it }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            // ── Título del día ────────────────────────────────────────────────
            val calLabel = Calendar.getInstance().apply { timeInMillis = selectedDay }
            val dayLabel = "%02d/%02d/%04d".format(
                calLabel.get(Calendar.DAY_OF_MONTH),
                calLabel.get(Calendar.MONTH) + 1,
                calLabel.get(Calendar.YEAR)
            )
            Text(
                "Rutinas · $dayLabel",
                color      = TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize   = 12.sp,
                modifier   = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── Lista de eventos del día ───────────────────────────────────────
            if (daySchedules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Event,
                            contentDescription = null,
                            tint     = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Sin rutinas para este día", color = TextSecondary, fontSize = 14.sp)
                        Text(
                            "Pulsa + para programar una",
                            color    = TextSecondary.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.weight(1f).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(daySchedules, key = { it.id }) { s ->
                        ScheduleCard(s) { viewModel.deleteSchedule(context, s) }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        // ── FAB ───────────────────────────────────────────────────────────────
        FloatingActionButton(
            onClick        = { preselectedRoutine = null; showDialog = true },
            containerColor = AccentCyan,
            contentColor   = Color.Black,
            modifier       = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(Icons.Default.Add, "Programar rutina")
        }
    }

    if (showDialog) {
        ScheduleDialog(
            routines           = routines,
            preselectedRoutine = preselectedRoutine,
            initialDay         = selectedDay,
            onDismiss          = { showDialog = false },
            onConfirm          = { schedule ->
                viewModel.saveSchedule(context, schedule)
                showDialog = false
            }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// CARD DE EVENTO PROGRAMADO
// ════════════════════════════════════════════════════════════════════════════════
@Composable
fun ScheduleCard(schedule: ScheduledRoutine, onDelete: () -> Unit) {
    val recLabel = when (schedule.recurrenceType) {
        RecurrenceType.ONCE         -> "Una vez"
        RecurrenceType.EVERY_N_DAYS -> "Cada ${schedule.intervalDays} días"
        RecurrenceType.WEEKLY_DAY   -> "Cada ${weekDayName(schedule.weekDay)}"
        else                        -> ""
    }

    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0D2020)),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AccentCyan.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint     = AccentCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    schedule.routineName,
                    color      = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccessTime, null, tint = AccentCyan, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "%02d:%02d".format(schedule.hourOfDay, schedule.minute),
                        color      = AccentCyan,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Repeat, null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(recLabel, color = TextSecondary, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.7f))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// GRID MENSUAL
// ════════════════════════════════════════════════════════════════════════════════
@Composable
fun MonthlyCalendar(
    selectedDay   : Long,
    scheduledDays : Set<Long>,
    onDaySelected : (Long) -> Unit
) {
    var displayMonth by remember {
        mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = selectedDay }.get(Calendar.MONTH))
    }
    var displayYear by remember {
        mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = selectedDay }.get(Calendar.YEAR))
    }

    val monthNames = arrayOf(
        "Enero","Febrero","Marzo","Abril","Mayo","Junio",
        "Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre"
    )

    Column(modifier = Modifier.padding(horizontal = 24.dp)) {

        // ── Cabecera mes ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (displayMonth == 0) { displayMonth = 11; displayYear-- } else displayMonth--
            }) {
                Icon(Icons.Default.ChevronLeft, null, tint = AccentWhite)
            }
            Text(
                "${monthNames[displayMonth]} $displayYear",
                color      = AccentWhite,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                modifier   = Modifier.weight(1f),
                textAlign  = TextAlign.Center
            )
            IconButton(onClick = {
                if (displayMonth == 11) { displayMonth = 0; displayYear++ } else displayMonth++
            }) {
                Icon(Icons.Default.ChevronRight, null, tint = AccentWhite)
            }
        }

        // ── Días de semana ────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("L","M","X","J","V","S","D").forEach { h ->
                Text(
                    h,
                    modifier   = Modifier.weight(1f),
                    textAlign  = TextAlign.Center,
                    color      = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 12.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // ── Grid de días ──────────────────────────────────────────────────────
        val cells = buildCalendarCells(displayYear, displayMonth)
        val today = todayMidnight()

        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { dayMs ->
                    val isSelected  = dayMs == selectedDay
                    val isToday     = dayMs == today
                    val hasSchedule = dayMs != 0L && scheduledDays.any { isSameDay(it, dayMs) }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .then(when {
                                isSelected -> Modifier.background(AccentCyan, CircleShape)
                                isToday    -> Modifier.border(1.dp, AccentCyan.copy(alpha = 0.6f), CircleShape)
                                else       -> Modifier
                            })
                            .clickable(enabled = dayMs != 0L) { if (dayMs != 0L) onDaySelected(dayMs) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayMs != 0L) {
                            val num = Calendar.getInstance().apply { timeInMillis = dayMs }
                                .get(Calendar.DAY_OF_MONTH)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$num",
                                    color      = if (isSelected) Color.Black else AccentWhite,
                                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    fontSize   = 14.sp
                                )
                                if (hasSchedule) {
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .background(
                                                if (isSelected) Color.Black.copy(alpha = 0.5f) else AccentCyan,
                                                CircleShape
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// DIÁLOGO PARA PROGRAMAR RUTINA
// ════════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(
    routines           : List<WorkoutSession>,
    preselectedRoutine : WorkoutSession?,
    initialDay         : Long,
    onDismiss          : () -> Unit,
    onConfirm          : (ScheduledRoutine) -> Unit
) {
    val context = LocalContext.current

    var selectedRoutine by remember { mutableStateOf(preselectedRoutine ?: routines.firstOrNull()) }
    var startDateMs     by remember { mutableStateOf(initialDay) }
    var hourOfDay       by remember { mutableIntStateOf(10) }
    var minute          by remember { mutableIntStateOf(0) }
    var recurrence      by remember { mutableStateOf(RecurrenceType.ONCE) }
    var intervalDays    by remember { mutableIntStateOf(1) }
    var weekDaySelected by remember {
        mutableIntStateOf(
            Calendar.getInstance().apply { timeInMillis = initialDay }.get(Calendar.DAY_OF_WEEK)
        )
    }
    var durationMonths  by remember { mutableIntStateOf(2) }
    var routineDropdown by remember { mutableStateOf(false) }

    fun computedEndDate(): Long = if (recurrence == RecurrenceType.ONCE) 0L else
        Calendar.getInstance().apply {
            timeInMillis = startDateMs
            add(Calendar.MONTH, durationMonths)
        }.timeInMillis

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF111111)) {
            LazyColumn(
                modifier            = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Título ──────────────────────────────────────────────────
                item {
                    Text("Programar rutina", color = AccentWhite, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }

                // ── Selector de rutina ──────────────────────────────────────
                item {
                    Text("Rutina", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(
                            onClick  = { routineDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(12.dp),
                            border   = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                        ) {
                            Text(
                                selectedRoutine?.name ?: "Sin rutinas creadas",
                                modifier  = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                                color     = TextPrimary
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded         = routineDropdown,
                            onDismissRequest = { routineDropdown = false }
                        ) {
                            routines.forEach { r ->
                                DropdownMenuItem(
                                    text    = { Text(r.name, color = Color(0xFF1C1C1E)) },
                                    onClick = { selectedRoutine = r; routineDropdown = false }
                                )
                            }
                        }
                    }
                }

                // ── Fecha de inicio ─────────────────────────────────────────
                item {
                    val dateCal = Calendar.getInstance().apply { timeInMillis = startDateMs }
                    val dateLabel = "%02d/%02d/%04d".format(
                        dateCal.get(Calendar.DAY_OF_MONTH),
                        dateCal.get(Calendar.MONTH) + 1,
                        dateCal.get(Calendar.YEAR)
                    )
                    Text("Fecha de inicio", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick  = {
                            val c = Calendar.getInstance().apply { timeInMillis = startDateMs }
                            android.app.DatePickerDialog(
                                context,
                                { _: DatePicker, y, m, d ->
                                    startDateMs = Calendar.getInstance().apply {
                                        set(y, m, d, 0, 0, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                    ) {
                        Icon(Icons.Default.CalendarToday, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(dateLabel, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, color = TextPrimary)
                    }
                }

                // ── Hora de notificación ────────────────────────────────────
                item {
                    Text("Hora de la notificación", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick  = {
                            android.app.TimePickerDialog(
                                context,
                                { _, h, m -> hourOfDay = h; minute = m },
                                hourOfDay, minute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                    ) {
                        Icon(Icons.Default.AccessTime, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "%02d:%02d".format(hourOfDay, minute),
                            modifier  = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            color     = TextPrimary
                        )
                    }
                }

                // ── Tipo de recurrencia ─────────────────────────────────────
                item {
                    Text("Repetición", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RecurrenceType.ONCE         to "Una vez",
                            RecurrenceType.EVERY_N_DAYS to "Cada X días",
                            RecurrenceType.WEEKLY_DAY   to "Semanal"
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = recurrence == type,
                                onClick  = { recurrence = type },
                                label    = { Text(label, fontSize = 11.sp) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    containerColor         = Color(0xFF1A1A1A),
                                    labelColor             = TextSecondary,
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor     = Color.Black
                                )
                            )
                        }
                    }
                }

                // ── Opciones de recurrencia ─────────────────────────────────
                item {
                    AnimatedVisibility(recurrence == RecurrenceType.EVERY_N_DAYS, enter = fadeIn(), exit = fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cada cuántos días", color = TextSecondary, fontSize = 12.sp)
                            Row(
                                verticalAlignment    = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { if (intervalDays > 1) intervalDays-- }) {
                                    Icon(Icons.Default.Remove, null, tint = AccentCyan)
                                }
                                Text("$intervalDays", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                IconButton(onClick = { intervalDays++ }) {
                                    Icon(Icons.Default.Add, null, tint = AccentCyan)
                                }
                            }
                            DurationPicker(durationMonths) { durationMonths = it }
                        }
                    }
                    Spacer(Modifier.height(0.dp)) // fuerza recomposición entre las dos animaciones
                    AnimatedVisibility(recurrence == RecurrenceType.WEEKLY_DAY, enter = fadeIn(), exit = fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Día de la semana", color = TextSecondary, fontSize = 12.sp)
                            WeekDaySelector(weekDaySelected) { weekDaySelected = it }
                            Spacer(Modifier.height(4.dp))
                            DurationPicker(durationMonths) { durationMonths = it }
                        }
                    }
                }

                // ── Botones ─────────────────────────────────────────────────
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(14.dp),
                            border   = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
                        ) {
                            Text("Cancelar", color = TextSecondary)
                        }
                        Button(
                            onClick  = {
                                onConfirm(
                                    ScheduledRoutine(
                                        routineId      = selectedRoutine?.id ?: "",
                                        routineName    = selectedRoutine?.name ?: "",
                                        startDate      = startDateMs,
                                        hourOfDay      = hourOfDay,
                                        minute         = minute,
                                        recurrenceType = recurrence,
                                        intervalDays   = intervalDays,
                                        weekDay        = weekDaySelected,
                                        endDate        = computedEndDate()
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(14.dp),
                            enabled  = selectedRoutine != null,
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = AccentCyan,
                                contentColor   = Color.Black
                            )
                        ) {
                            Text("Guardar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ── Selector de día de la semana ──────────────────────────────────────────────
@Composable
fun WeekDaySelector(selected: Int, onSelect: (Int) -> Unit) {
    val days = listOf(
        Calendar.MONDAY    to "L",
        Calendar.TUESDAY   to "M",
        Calendar.WEDNESDAY to "X",
        Calendar.THURSDAY  to "J",
        Calendar.FRIDAY    to "V",
        Calendar.SATURDAY  to "S",
        Calendar.SUNDAY    to "D"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { (calDay, label) ->
            val isSelected = selected == calDay
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(if (isSelected) AccentCyan else Color(0xFF1A1A1A), CircleShape)
                    .clickable { onSelect(calDay) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color      = if (isSelected) Color.Black else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp
                )
            }
        }
    }
}

// ── Selector de duración en meses ─────────────────────────────────────────────
@Composable
fun DurationPicker(months: Int, onMonthsChange: (Int) -> Unit) {
    Row(
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Durante", color = TextSecondary, fontSize = 12.sp)
        IconButton(onClick = { if (months > 1) onMonthsChange(months - 1) }) {
            Icon(Icons.Default.Remove, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
        }
        Text("$months meses", color = TextPrimary, fontWeight = FontWeight.Bold)
        IconButton(onClick = { if (months < 24) onMonthsChange(months + 1) }) {
            Icon(Icons.Default.Add, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UTILIDADES PURAS (no @Composable)
// ════════════════════════════════════════════════════════════════════════════════

fun todayMidnight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
           ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

fun isRecurringOnDay(s: ScheduledRoutine, dayMs: Long): Boolean {
    if (dayMs < s.startDate) return false
    if (s.endDate > 0 && dayMs > s.endDate) return false
    return when (s.recurrenceType) {
        RecurrenceType.EVERY_N_DAYS -> {
            val diffDays = (dayMs - s.startDate) / 86_400_000L
            diffDays >= 0 && diffDays % s.intervalDays == 0L
        }
        RecurrenceType.WEEKLY_DAY -> {
            Calendar.getInstance().apply { timeInMillis = dayMs }
                .get(Calendar.DAY_OF_WEEK) == s.weekDay
        }
        else -> false
    }
}

fun buildScheduleMap(schedules: List<ScheduledRoutine>): Map<Long, List<ScheduledRoutine>> =
    schedules.groupBy { midnight(it.startDate) }

fun midnight(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun buildCalendarCells(year: Int, month: Int): List<Long> {
    val cells = mutableListOf<Long>()
    val cal   = Calendar.getInstance().apply { set(year, month, 1) }
    // Ajustar a Lunes = columna 0
    var firstDow = cal.get(Calendar.DAY_OF_WEEK) - 2
    if (firstDow < 0) firstDow = 6
    repeat(firstDow) { cells.add(0L) }

    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    for (d in 1..daysInMonth) {
        cells.add(Calendar.getInstance().apply {
            set(year, month, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    while (cells.size % 7 != 0) cells.add(0L)
    return cells
}

fun weekDayName(calDay: Int): String = when (calDay) {
    Calendar.MONDAY    -> "lunes"
    Calendar.TUESDAY   -> "martes"
    Calendar.WEDNESDAY -> "miércoles"
    Calendar.THURSDAY  -> "jueves"
    Calendar.FRIDAY    -> "viernes"
    Calendar.SATURDAY  -> "sábado"
    Calendar.SUNDAY    -> "domingo"
    else               -> "?"
}
