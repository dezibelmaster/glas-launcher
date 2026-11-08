package de.glas.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The launcher's frosted glass: a light, see-through card with a fine bright edge. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = .20f), tint.copy(alpha = .09f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .35f), Color.White.copy(alpha = .08f))), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

/** Background colours after the weather, like the launcher's drawn backgrounds, kept dark for the watch. */
fun sky(code: Int?, isDay: Boolean): Brush {
    val colors = when {
        code == null -> listOf(0xFF1C2433, 0xFF06080C)
        code in 95..99 -> listOf(0xFF3A3150, 0xFF0C0A14)
        code in 71..77 || code == 85 || code == 86 -> if (isDay) listOf(0xFF6F8296, 0xFF1E2833) else listOf(0xFF2B3542, 0xFF07090D)
        code >= 45 -> if (isDay) listOf(0xFF45556A, 0xFF121820) else listOf(0xFF1F2733, 0xFF050709)
        code == 3 -> if (isDay) listOf(0xFF566170, 0xFF1A1E25) else listOf(0xFF222831, 0xFF06070A)
        !isDay -> listOf(0xFF14224A, 0xFF03050C)
        else -> listOf(0xFF2F6DBF, 0xFF0E2348)
    }
    return Brush.verticalGradient(colors.map { Color(it) })
}

val Dim = Color.White.copy(alpha = .7f)
val Warn = Color(0xFFFFC94A)
