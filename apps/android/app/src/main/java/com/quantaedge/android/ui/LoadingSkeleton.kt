package com.quantaedge.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A theme-aware placeholder for the initial loading state of content lists.
 * Keep it static and lightweight so it does not add animation work to low-end devices.
 */
@Composable
internal fun CourseListSkeleton(
    loadingDescription: String,
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = loadingDescription },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows.coerceIn(1, 6)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkeletonBar(fraction = 0.56f, height = 18.dp)
                    SkeletonBar(fraction = 0.86f, height = 10.dp)
                    SkeletonBar(fraction = 0.70f, height = 10.dp)
                    SkeletonBar(fraction = 0.42f, height = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction.coerceIn(0.12f, 1f))
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
    )
}
