package com.quantaedge.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantaedge.android.R
import com.quantaedge.android.data.InstitutionBrandIdentity

/** Provided by the app after resolving an explicit school link or a learner's sole active membership. */
val LocalInstitutionBrandIdentity = staticCompositionLocalOf<InstitutionBrandIdentity?> { null }

/**
 * Central identity lockup.
 * Institution identity replaces the platform lockup only within a resolved institution context.
 * Theme, colour presets and contrast remain managed by the shared QuantaEdge theme system.
 */
@Composable
fun QuantaEdgeBrand(
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    centered: Boolean = false
) {
    val institution = LocalInstitutionBrandIdentity.current
    val markSize = if (compact) 29.dp else 42.dp
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            when {
                institution?.logo != null -> Image(
                    bitmap = institution.logo.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(markSize).clip(RoundedCornerShape(if (compact) 7.dp else 10.dp)),
                    contentScale = ContentScale.Fit
                )
                institution != null -> Box(
                    modifier = Modifier.size(markSize).clip(RoundedCornerShape(if (compact) 7.dp else 10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = institution.displayName.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 15.sp else 21.sp
                    )
                }
                else -> Image(
                    painter = painterResource(R.drawable.quantaedge_icon),
                    contentDescription = null,
                    modifier = Modifier.size(markSize),
                    contentScale = ContentScale.Fit
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                if (institution != null) {
                    Text(
                        text = institution.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 17.sp else 23.sp,
                        lineHeight = if (compact) 20.sp else 27.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
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
}
