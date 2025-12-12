package com.localgrok.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgrok.ui.theme.InterFont
import com.localgrok.ui.theme.LocalAppColors
import com.localgrok.ui.theme.LocalGrokColors

// Dark Carbon color for input container
val DarkCarbon = Color(0xFF121212)

// Thinking text color - subtle darker grey distinct from white
val ThinkingGrey = Color(0xFF808080)

// Brain toggle colors
val BrainYellow = Color(0xFFFFD600)

// ═══════════════════════════════════════════════════════════════════════════
// CUSTOM ICONS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun TwoLineMenuIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val lineSpacing = 6.dp.toPx()
        val padding = 4.dp.toPx()

        // Top line
        drawLine(
            color = tint,
            start = Offset(padding, size.height / 2 - lineSpacing / 2),
            end = Offset(size.width - padding, size.height / 2 - lineSpacing / 2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Bottom line
        drawLine(
            color = tint,
            start = Offset(padding, size.height / 2 + lineSpacing / 2),
            end = Offset(size.width - padding, size.height / 2 + lineSpacing / 2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TOP BAR COMPONENTS - GROK STYLE
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun GrokTopBar(
    onMenuClick: () -> Unit,
    onNewConversationClick: () -> Unit,
    onImagineUnavailable: () -> Unit = {},
    colors: LocalGrokColors = LocalAppColors.current,
    modifier: Modifier = Modifier
) {
    var isAskActive by remember { mutableStateOf(true) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Hamburger Menu (2 lines)
        IconButton(onClick = onMenuClick) {
            TwoLineMenuIcon(
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Toggle Pill - Ask/Imagine
        TogglePill(
            leftLabel = "Ask",
            rightLabel = "Imagine",
            isLeftActive = isAskActive,
            onToggle = { toLeft ->
                if (toLeft) {
                    // Allow toggling to Ask
                    isAskActive = true
                } else {
                    // Prevent toggling to Imagine, surface unified snackbar message
                    onImagineUnavailable()
                }
            },
            leftIcon = Icons.Outlined.ChatBubbleOutline,
            rightIcon = Icons.Outlined.AutoAwesome,
            colors = colors
        )

        // New Conversation Icon
        IconButton(onClick = onNewConversationClick) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New conversation",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun TogglePill(
    leftLabel: String,
    rightLabel: String,
    isLeftActive: Boolean,
    onToggle: (Boolean) -> Unit,
    leftIcon: ImageVector,
    rightIcon: ImageVector,
    colors: LocalGrokColors = LocalAppColors.current,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.pillInactive)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Option
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(17.dp))
                .background(if (isLeftActive) colors.pillActive else Color.Transparent)
                .clickable { onToggle(true) }
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = leftIcon,
                contentDescription = null,
                tint = if (isLeftActive) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = leftLabel,
                fontFamily = InterFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (isLeftActive) colors.textPrimary else colors.textSecondary
            )
        }

        // Right Option
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(17.dp))
                .background(if (!isLeftActive) colors.pillActive else Color.Transparent)
                .clickable { onToggle(false) }
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = rightIcon,
                contentDescription = null,
                tint = if (!isLeftActive) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = rightLabel,
                fontFamily = InterFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (!isLeftActive) colors.textPrimary else colors.textSecondary
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════
// LEGACY INPUT CLUSTER (kept for backwards compatibility)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun GrokInputCluster(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modelName: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    // This is kept for backwards compatibility - delegates to UnifiedInputBar
    var selectedModel by remember {
        mutableStateOf(
            com.localgrok.ui.components.chat.ModelOption(
                id = "default",
                displayName = modelName.take(15),
                subtitle = "",
                icon = Icons.Outlined.Lightbulb,
                modelId = modelName
            )
        )
    }

    com.localgrok.ui.components.chat.UnifiedInputBar(
        value = value,
        onValueChange = onValueChange,
        onSend = onSend,
        selectedModel = selectedModel,
        onModelSelected = { model -> selectedModel = model },
        enabled = enabled,
        modifier = modifier
    )
}


@Composable
fun StreamingIndicator() {
    val colors = LocalAppColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Text(
        text = "generating...",
        style = MaterialTheme.typography.labelSmall,
        color = colors.textSecondary.copy(alpha = alpha)
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// STATUS INDICATORS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ConnectionStatusBadge(
    isConnected: Boolean,
    serverIp: String?,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.darkGrey)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (isConnected) colors.success else colors.textDim,
                    shape = RoundedCornerShape(50)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isConnected) "Connected" else "Offline",
            style = MaterialTheme.typography.labelSmall,
            color = if (isConnected) colors.success else colors.textSecondary
        )
        if (serverIp != null) {
            Text(
                text = " • $serverIp",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textDim
            )
        }
    }
}

@Composable
fun EmptyChatPlaceholder(
    selectedModel: com.localgrok.ui.components.chat.ModelOption = com.localgrok.ui.components.chat.DEFAULT_MODEL,
    inputBarHeight: androidx.compose.ui.unit.Dp = 130.dp,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Display the selected model's icon as a faint background element
        // Offset upward by half the input bar height to visually center in the available space
        Icon(
            imageVector = selectedModel.icon,
            contentDescription = selectedModel.displayName,
            tint = colors.borderGrey.copy(alpha = 0.3f),
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    translationY = -(inputBarHeight.toPx() / 2f)
                }
        )
    }
}
