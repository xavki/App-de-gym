package com.gymflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay

@Composable
fun ModernButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = AccentWhite,
    contentColor: Color = Color.Black,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = Color(0xFF222222)
        )
    ) {
        Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

/**
 * Imagen pequeña estática — para listas y selección de ejercicios
 */
@Composable
fun ExerciseImageSmall(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.FitnessCenter,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * Imagen grande animada — alterna entre imagen1 e imagen2 cada 1.2 segundos
 * simula el efecto GIF con las dos fotos de la base de datos
 */
@Composable
fun ExerciseImageAnimated(
    imageUrl: String?,
    gifUrl: String?,
    modifier: Modifier = Modifier
) {
    // Si imageUrl y gifUrl son distintas, alternamos entre ellas
    val frames = listOfNotNull(imageUrl, gifUrl?.takeIf { it != imageUrl })
    var currentFrame by remember { mutableIntStateOf(0) }

    // Animación: cambia de frame cada 1200ms si hay más de uno
    LaunchedEffect(frames) {
        if (frames.size > 1) {
            while (true) {
                delay(1200L)
                currentFrame = (currentFrame + 1) % frames.size
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        if (frames.isNotEmpty()) {
            // Imagen actual
            var isLoading by remember { mutableStateOf(true) }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(frames[currentFrame])
                    .crossfade(400)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                }
            )
            if (isLoading) {
                CircularProgressIndicator(
                    color = AccentCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
        } else {
            // Placeholder si no hay imagen
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
fun InfoChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
