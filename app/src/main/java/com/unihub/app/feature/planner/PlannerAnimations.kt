package com.unihub.app.feature.planner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Subtle planner-only motion, inspired by the Files screen reveal and press feedback.
 * Each keyed item reveals once; only the first eight items receive a short stagger.
 */
@Composable
internal fun Modifier.plannerReveal(
    key: Any,
    index: Int,
    playedKeys: MutableSet<Any>
): Modifier {
    val alreadyPlayed = remember(key) { key in playedKeys }
    val progress = remember(key) { Animatable(if (alreadyPlayed) 1f else 0f) }

    LaunchedEffect(key) {
        if (!alreadyPlayed) {
            playedKeys.add(key)
            if (index < 8) delay(index.coerceAtLeast(0) * 34L + 24L)
            progress.animateTo(1f, tween(durationMillis = 340))
        }
    }

    return graphicsLayer {
        val value = progress.value.coerceIn(0f, 1f)
        alpha = value
        translationY = (1f - value) * 18.dp.toPx()
        val scale = 0.985f + 0.015f * value
        scaleX = scale
        scaleY = scale
    }
}

/** Provides a brief, non-blocking scale response while a planner card is pressed. */
@Composable
internal fun Modifier.plannerPressScale(
    interactionSource: MutableInteractionSource
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "planner-card-press"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Fades and shifts changing planner labels instead of replacing text abruptly. */
@Composable
internal fun PlannerAnimatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textDecoration: TextDecoration? = null
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 5 }) togetherWith
                (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 5 })
        },
        label = "planner-text-change"
    ) { displayedText ->
        Text(
            text = displayedText,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = overflow,
            textDecoration = textDecoration
        )
    }
}