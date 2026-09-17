package com.teamproject1.dailyexpensetracker.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A subtle repeating dot texture drawn directly via Canvas — chosen over a
 * raster/vector image asset since we can't preview-render an actual image
 * file in this environment before shipping it; a programmatic pattern is
 * guaranteed correct by construction and needs no asset pipeline at all.
 * Applied as a background wrapper on Dashboard and Book Selector, per the
 * locked redesign decision to apply consistently across tile-grid screens.
 */
@Composable
fun DottedTextureBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val baseColor = MaterialTheme.colorScheme.background
    val dotColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().background(baseColor)) {
            drawDotGrid(dotColor)
        }
        content()
    }
}

private fun DrawScope.drawDotGrid(color: Color) {
    val spacing = 28f
    val radius = 1.6f
    var y = 0f
    while (y < size.height) {
        var x = 0f
        while (x < size.width) {
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            x += spacing
        }
        y += spacing
    }
}
