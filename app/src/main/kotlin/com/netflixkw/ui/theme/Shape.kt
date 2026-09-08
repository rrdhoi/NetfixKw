package com.netflixkw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    small = RoundedCornerShape(4.dp), // 0.25rem = 4dp
    medium = RoundedCornerShape(12.dp), // 0.75rem = 12dp
    large = RoundedCornerShape(16.dp), // 1rem = 16dp
    extraLarge = RoundedCornerShape(24.dp) // 1.5rem = 24dp
)

val shapeDefault = RoundedCornerShape(8.dp) // 0.5rem = 8dp
val shapePill = RoundedCornerShape(percent = 50) // full = 9999px
