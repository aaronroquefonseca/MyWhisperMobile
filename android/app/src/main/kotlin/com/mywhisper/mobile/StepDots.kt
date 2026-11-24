package com.mywhisper.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Practice-mode "working" time before the example text lands: long enough to read as real. */
internal const val TUTORIAL_WORKING_MS = 300L

/** Step dots for paged flows. */
@Composable
internal fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 20.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}
