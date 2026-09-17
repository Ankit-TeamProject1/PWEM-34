package com.teamproject1.dailyexpensetracker.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Extruded 3D tile: soft drop shadow, responsive tap-depth (scales/flattens
 * on press). This is the single shared component behind every Dashboard
 * tile — Accounts, Add Expense, Transfer, Budget, Analytics, Recurring,
 * Settings — so all seven stay visually consistent for free. Also used by
 * Book Selector's cards, which additionally need long-press for the
 * Rename/Archive menu — onLongClick is optional so existing single-tap
 * tiles are unaffected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeumorphicTile(
    modifier: Modifier = Modifier,
    tileHeight: androidx.compose.ui.unit.Dp = 120.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val elevation by androidx.compose.animation.core.animateFloatAsState(if (isPressed) 2f else 8f, label = "tileElevation")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tileHeight)
            .shadow(
                elevation = elevation.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.15f),
                spotColor = Color.Black.copy(alpha = 0.15f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(16.dp)
    ) {
        content()
    }
}
