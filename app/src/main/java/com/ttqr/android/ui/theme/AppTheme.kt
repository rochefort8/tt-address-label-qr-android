package com.ttqr.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFF7AE2FF),
    secondary = Color(0xFFB2CDE0),
    background = Color(0xFF081F2C),
    surface = Color(0xFF10384A),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content,
    )
}
