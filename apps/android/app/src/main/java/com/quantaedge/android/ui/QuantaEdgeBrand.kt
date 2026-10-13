package com.quantaedge.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.quantaedge.android.R

/**
 * Single source for QuantaEdge's Android wordmark + readable tagline.
 * The web uses the matching transparent wordmark artwork followed by the same
 * real text tagline; keep all Android placements on this component as well.
 */
@Composable
fun QuantaEdgeBrand(
    modifier: Modifier = Modifier,
    compact: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.quantaedge_wordmark),
            contentDescription = null,
            modifier = Modifier
                .width(if (compact) 148.dp else 248.dp)
                .heightIn(max = if (compact) 26.dp else 48.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = "Smarter Decisions. Greater Growth.",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = if (compact) 8.sp else 12.sp,
            lineHeight = if (compact) 10.sp else 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
