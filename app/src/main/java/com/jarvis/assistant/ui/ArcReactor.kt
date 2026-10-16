package com.jarvis.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.core.AssistantState
import com.jarvis.assistant.ui.theme.JarvisCyan

/**
 * Ortadaki nabız atan "arc reactor". Asistan dinlerken/konuşurken
 * daha hızlı ve parlak atar.
 */
@Composable
fun ArcReactor(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
) {
    val active = state != AssistantState.IDLE
    val transition = rememberInfiniteTransition(label = "reactor")
    val pulse = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 450 else 1600),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 2500 else 9000, easing = LinearEasing)),
        label = "spin",
    )
    val glow = when (state) {
        AssistantState.IDLE -> 0.35f
        AssistantState.LISTENING -> 0.9f
        AssistantState.THINKING -> 0.6f
        AssistantState.SPEAKING -> 0.75f
    }

    Canvas(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2 * pulse.value

        // Dış parlama
        drawCircle(
            brush = Brush.radialGradient(
                listOf(JarvisCyan.copy(alpha = glow), Color.Transparent),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
        // Dönen parçalı halka
        rotate(spin.value, c) {
            for (i in 0 until 10) {
                drawArc(
                    color = JarvisCyan.copy(alpha = 0.85f),
                    startAngle = i * 36f,
                    sweepAngle = 22f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.72f, c.y - r * 0.72f),
                    size = androidx.compose.ui.geometry.Size(r * 1.44f, r * 1.44f),
                    style = Stroke(width = r * 0.08f),
                )
            }
        }
        // İç halka ve çekirdek
        drawCircle(JarvisCyan.copy(alpha = 0.6f), radius = r * 0.5f, center = c, style = Stroke(r * 0.04f))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White, JarvisCyan, Color.Transparent),
                center = c,
                radius = r * 0.38f,
            ),
            radius = r * 0.38f,
            center = c,
        )
    }
}
