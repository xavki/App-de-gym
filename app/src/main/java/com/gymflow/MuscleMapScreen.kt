package com.gymflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════════════════
// Mapa muscular de Progreso: figura frontal y posterior (MuscleAtlas) coloreadas
// con la actividad de los últimos 7 días (MuscleActivity).
// ════════════════════════════════════════════════════════════════════════════

/** Nombre → (mainGroup, musclesUsed) del catálogo y de los ejercicios personalizados. */
fun muscleCatalog(global: List<ExerciseDefinition>, custom: List<CustomExercise>): Map<String, Pair<String, String>> =
    global.associate { it.name to (it.mainGroup to it.musclesUsed) } +
        custom.associate { it.name to (it.mainGroup to it.musclesUsed) }

private class ParsedPart(val group: String?, val path: Path)

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
private val silhouettePath by lazy { parse(MuscleAtlas.SILHOUETTE) }
private val frontParts by lazy { MuscleAtlas.front.map { ParsedPart(it.group, parse(it.d)) } }
private val backParts by lazy { MuscleAtlas.back.map { ParsedPart(it.group, parse(it.d)) } }

/** Colores del mapa; la actividad va del gris del músculo al azul principal del tema. */
private class MapColors(pal: AppPalette) {
    val body = if (pal.isDark) Color(0xFF2A3850) else Color(0xFFE6ECF3)
    val idle = if (pal.isDark) Color(0xFF3B4D69) else Color(0xFFCBD6E4)
    val untracked = lerp(body, idle, 0.5f)
    val gap = pal.surface
    val accent = pal.accent
    // Misma escala que el mapa anterior (0,25 + 0,75 · intensidad), aplicada sobre el gris
    fun of(v: Float): Color = if (v <= 0f) idle else lerp(idle, accent, 0.25f + v.coerceIn(0f, 1f) * 0.75f)
}

private const val GAP = 46f // separación entre figuras, en unidades del atlas

private fun series(n: Int) = if (n == 1) "1 serie" else "$n series"

@Composable
fun MuscleMapCard(history: List<WorkoutSession>, catalog: Map<String, Pair<String, String>>) {
    val activity = remember(history.size, catalog.size) { MuscleActivity.compute(history, catalog) }
    val colors = MapColors(LocalPalette.current)
    // En horizontal la altura manda: las figuras no pasan de media pantalla
    val windowH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val cap = min(300.dp, windowH * 0.5f)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.muscle_map_title),
                    color = TextSecondary, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.muscle_map_subtitle), color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 560.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BodyFigures(activity, colors, Modifier.weight(1f), cap = cap)
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) { MuscleList(activity, colors); Legend(colors) }
                    }
                } else {
                    Column {
                        BodyFigures(activity, colors, Modifier.fillMaxWidth(), cap = cap)
                        Spacer(Modifier.height(12.dp))
                        Legend(colors)
                        Spacer(Modifier.height(12.dp))
                        MuscleList(activity, colors)
                    }
                }
            }
            val extra = buildList {
                if (activity.cardioSets > 0) add("Cardio: ${series(activity.cardioSets)} (no se dibuja)")
                if (activity.unassignedSets > 0) add("Sin grupo muscular: ${series(activity.unassignedSets)}")
            }
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(extra.joinToString(" · "), color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

/** Las dos figuras en un solo lienzo, con su rótulo debajo. */
@Composable
private fun BodyFigures(activity: MuscleActivity, colors: MapColors, modifier: Modifier, cap: Dp) {
    val totalW = MuscleAtlas.WIDTH * 2 + GAP
    val desc = MuscleActivity.GROUPS.joinToString { (id, name) -> "$name ${series(activity.sets[id] ?: 0)}" }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val h = min(maxWidth * (MuscleAtlas.HEIGHT / totalW), cap)
        val w = h * (totalW / MuscleAtlas.HEIGHT)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(w, h).semantics { contentDescription = "Mapa muscular, últimos 7 días: $desc" }) {
                val s = size.height / MuscleAtlas.HEIGHT
                scale(s, s, pivot = Offset.Zero) {
                    drawFigure(frontParts, activity, colors)
                    translate(left = MuscleAtlas.WIDTH + GAP) { drawFigure(backParts, activity, colors) }
                }
            }
            Row(Modifier.width(w)) {
                Text("Frontal", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Posterior", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun DrawScope.drawFigure(parts: List<ParsedPart>, activity: MuscleActivity, colors: MapColors) {
    val gap = Stroke(width = 1.4f)
    for (mirror in listOf(false, true)) {
        scale(if (mirror) -1f else 1f, 1f, pivot = Offset(MuscleAtlas.WIDTH / 2, 0f)) {
            drawPath(silhouettePath, colors.body)
            parts.forEach { p ->
                val c = if (p.group == null) colors.untracked else colors.of(activity.intensity[p.group] ?: 0f)
                drawPath(p.path, c)
                drawPath(p.path, colors.gap, style = gap)
            }
        }
    }
}

@Composable
private fun Legend(colors: MapColors) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(colors.of(0f), stringResource(R.string.muscle_none))
            LegendItem(colors.of(0.5f), stringResource(R.string.muscle_light))
            LegendItem(colors.of(1f), stringResource(R.string.muscle_high))
        }
        Spacer(Modifier.height(4.dp))
        Text("Color relativo al grupo con más series en estos 7 días.", color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

/** Nombres en español con su color y las series reales del periodo, en dos columnas. */
@Composable
private fun MuscleList(activity: MuscleActivity, colors: MapColors) {
    val rows = MuscleActivity.GROUPS.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (id, name) ->
                    val n = activity.sets[id] ?: 0
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(colors.of(activity.intensity[id] ?: 0f), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (n == 0) "—" else "$n", color = if (n == 0) TextSecondary else TextPrimary,
                            fontSize = 13.sp, fontWeight = if (n == 0) FontWeight.Normal else FontWeight.SemiBold)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("Series por grupo (músculo principal de cada ejercicio).", color = TextSecondary, fontSize = 11.sp)
    }
}
