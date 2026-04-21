package com.gymflow

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun LoginScreen(
    auth: FirebaseAuth,
    db: FirebaseFirestore,
    onLoginSuccess: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onError: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Animación de pulso para el logo
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000),
                        Color(0xFF041515),
                        Color(0xFF082020),
                        Color(0xFF000000)
                    )
                )
            )
    ) {
        // Glow decorativo detrás del logo
        Box(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.TopCenter)
                .offset(y = 80.dp)
                .blur(80.dp)
                .background(AccentCyan.copy(alpha = glowAlpha), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo con animación
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.FitnessCenter,
                    null,
                    tint = AccentCyan.copy(alpha = 0.15f),
                    modifier = Modifier.size(120.dp).scale(logoScale)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.app_name),
                        color = AccentWhite,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.scale(logoScale)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.app_tagline),
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // Selector Login / Registro
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111), RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (!isRegistering) AccentCyan.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { isRegistering = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.login_tab_enter),
                        color = if (!isRegistering) AccentCyan else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isRegistering) AccentCyan.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { isRegistering = true }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.login_tab_register),
                        color = if (isRegistering) AccentCyan else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Campos
            if (isRegistering) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.login_full_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = loginFieldColors()
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.login_email)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = loginFieldColors()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null,
                            tint = TextSecondary
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = loginFieldColors()
            )

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { if(!isRegistering) rememberMe = !rememberMe }
            ) {
                Checkbox(
                    checked = rememberMe || isRegistering,
                    onCheckedChange = { if(!isRegistering) rememberMe = it },
                    colors = CheckboxDefaults.colors(checkedColor = AccentCyan, uncheckedColor = Color.DarkGray)
                )
                Text(stringResource(R.string.login_remember), color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))

            // Botón principal
            val loadingStr    = stringResource(R.string.login_loading)
            val createStr     = stringResource(R.string.login_create_account)
            val enterStr      = stringResource(R.string.login_enter)
            Button(
                onClick = {
                    val fE = email.trim(); val fP = password.trim()
                    if (fE.isNotBlank() && fP.isNotBlank()) {
                        isLoading = true
                        if (isRegistering) {
                            auth.createUserWithEmailAndPassword(fE, fP).addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    val userData = hashMapOf("uid" to auth.currentUser!!.uid, "name" to name.trim(), "email" to fE)
                                    db.collection("users").document(auth.currentUser!!.uid).set(userData).addOnCompleteListener { 
                                        isLoading = false
                                        if(it.isSuccessful) onLoginSuccess() 
                                        else onError(it.exception?.message ?: "Error Firestore") 
                                    }
                                } else { 
                                    isLoading = false
                                    onError(task.exception?.message ?: "Error Auth") 
                                }
                            }
                        } else {
                            auth.signInWithEmailAndPassword(fE, fP).addOnCompleteListener { task ->
                                isLoading = false
                                if (task.isSuccessful) {
                                    context.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE).edit().putBoolean("remember_me", rememberMe).apply()
                                    onLoginSuccess()
                                } else onError(task.exception?.message ?: "Error Login")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = Color.Black,
                    disabledContainerColor = AccentCyan.copy(alpha = 0.4f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (isLoading) loadingStr else if (isRegistering) createStr else enterStr,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Divider con "o"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray.copy(alpha = 0.5f))
                Text("  ${stringResource(R.string.login_or)}  ", color = TextSecondary, fontSize = 12.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray.copy(alpha = 0.5f))
            }

            Spacer(Modifier.height(16.dp))

            // Google
            OutlinedButton(
                onClick = onGoogleSignIn,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF333333))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Login, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.login_google), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = Color(0xFF2A2A2A),
    cursorColor = AccentCyan,
    focusedTextColor = AccentWhite,
    unfocusedTextColor = AccentWhite,
    focusedLabelColor = AccentCyan,
    unfocusedLabelColor = TextSecondary,
    focusedContainerColor = Color(0xFF0A0A0A),
    unfocusedContainerColor = Color(0xFF0A0A0A)
)
