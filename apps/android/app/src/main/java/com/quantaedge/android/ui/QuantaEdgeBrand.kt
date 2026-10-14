package com.quantaedge.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantaedge.android.R

/**
 * Single source for QuantaEdge's Android brand lockup.
 * Mirrors the web brand: canonical Q icon, unchanged QuantaEdge name in
 * approved navy/orange, and the unchanged English tagline. The legacy
 * wordmark PNG is incomplete and must not be used as a runtime drawable.
 */
@Composable
fun QuantaEdgeBrand(
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    centered: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.quantaedge_icon),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 29.dp else 42.dp),
                contentScale = ContentScale.Fit
            )
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF152038), fontWeight = FontWeight.ExtraBold)) {
                            append("Quanta")
                        }
                        withStyle(SpanStyle(color = Color(0xFFF97316), fontWeight = FontWeight.ExtraBold)) {
                            append("Edge")
                        }
                    },
                    fontSize = if (compact) 18.sp else 24.sp,
                    lineHeight = if (compact) 20.sp else 27.sp,
                    letterSpacing = if (compact) (-0.35).sp else (-0.45).sp,
                    maxLines = 1
                )
                Text(
                    text = "Smarter Decisions. Greater Growth.",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = if (compact) 8.sp else 12.sp,
                    lineHeight = if (compact) 10.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
