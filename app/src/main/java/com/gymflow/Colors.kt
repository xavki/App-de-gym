package com.gymflow

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════════
// Tema visual: claro por defecto, oscuro si el usuario lo elige (Perfil → Ajustes).
// Misma identidad que el panel web: azul principal y turquesa, fondos claros.
// Contraste: texto normal ≥ 4,5:1 sobre su superficie en los dos temas.
// ═══════════════════════════════════════════════════════════════════════════════

@Immutable
data class AppPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,     // campos, pistas de barras, chips
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,         // azul principal (botones, enlaces, selección)
    val onAccent: Color,       // texto/iconos sobre el azul
    val teal: Color,           // turquesa (series hechas, éxito suave)
    val danger: Color,
    val gold: Color,           // récords
    val success: Color,
)

val LightPalette = AppPalette(
    isDark = false,
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF1F5FA),
    border = Color(0xFFE4EAF2),
    borderStrong = Color(0xFFCFD9E6),
    textPrimary = Color(0xFF172338),
    textSecondary = Color(0xFF526178),
    accent = Color(0xFF2A62CC),      // 5,6:1 con blanco (el #3978E8 del panel se queda en 4,2:1)
    onAccent = Color(0xFFFFFFFF),
    teal = Color(0xFF0D7570),
    danger = Color(0xFFBF3636),
    gold = Color(0xFF8A5A00),
    success = Color(0xFF1E7A3C),
)

val DarkPalette = AppPalette(
    isDark = true,
    background = Color(0xFF101827),
    surface = Color(0xFF1B2638),
    surfaceAlt = Color(0xFF233148),
    border = Color(0xFF2E3D55),
    borderStrong = Color(0xFF3D4F6B),
    textPrimary = Color(0xFFF2F6FC),
    textSecondary = Color(0xFFB4C1D3),
    accent = Color(0xFF70A5FF),
    onAccent = Color(0xFF0B1424),
    teal = Color(0xFF42C8BE),
    danger = Color(0xFFF27474),
    gold = Color(0xFFF5C451),
    success = Color(0xFF5FD38A),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

// ─── Nombres usados por las pantallas ─────────────────────────────────────────
// Se conservan los nombres históricos (venían del tema negro) para no tocar cientos de
// líneas, pero ahora dependen del tema: "BackgroundDark" es el fondo, claro u oscuro.
val BackgroundDark: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
val SurfaceDark: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
val SurfaceAlt: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceAlt
val Border: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.border
val BorderStrong: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.borderStrong
/** Texto/iconos de máximo contraste (antes blanco). */
val AccentWhite: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
/** Color de acento principal (antes cian). */
val AccentCyan: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
val AccentTeal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.teal
val AccentRed: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.danger
val Gold: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.gold
val Success: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.success
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary

@Composable
fun GymFlowTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.teal, onSecondary = p.onAccent,
            background = p.background, onBackground = p.textPrimary, surface = p.surface, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.textSecondary, outline = p.borderStrong,
            outlineVariant = p.border, error = p.danger, surfaceContainerHigh = p.surface, surfaceContainer = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.teal, onSecondary = p.onAccent,
            background = p.background, onBackground = p.textPrimary, surface = p.surface, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.textSecondary, outline = p.borderStrong,
            outlineVariant = p.border, error = p.danger, surfaceContainerHigh = p.surface, surfaceContainer = p.surface,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Preferencia de tema: claro salvo que el usuario haya elegido oscuro. No sigue al sistema. */
object ThemePrefs {
    private const val KEY = "app_theme_dark"
    fun isDark(context: Context): Boolean =
        context.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setDark(context: Context, dark: Boolean) =
        context.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, dark).apply()
}
