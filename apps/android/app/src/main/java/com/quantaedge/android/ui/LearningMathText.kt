package com.quantaedge.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Displays the same simple a/b stacked-fraction notation used by the web MathText
 * component. Normal prose remains selectable/readable as text and unknown maths
 * stays untouched; only unambiguous integer fractions are transformed.
 */
@Composable
fun LearningMathText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current
) {
    val parts = remember(text) { splitInlineFractions(text) }
    val resolvedFontSize = if (style.fontSize == TextUnit.Unspecified) {
        MaterialTheme.typography.bodyLarge.fontSize
    } else style.fontSize

    val annotated = remember(text, parts) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (part.numerator == null || part.denominator == null) {
                    append(part.text)
                } else {
                    appendInlineContent(
                        "fraction-$index",
                        alternateText = "${part.numerator}/${part.denominator}"
                    )
                }
            }
        }
    }
    val inlineContent = remember(parts, resolvedFontSize, color) {
        buildMap<String, InlineTextContent> {
            parts.forEachIndexed { index, part ->
                val numerator = part.numerator ?: return@forEachIndexed
                val denominator = part.denominator ?: return@forEachIndexed
                val width = resolvedFontSize * (maxOf(numerator.length, denominator.length) * 0.58f + 0.5f)
                put(
                    "fraction-$index",
                    InlineTextContent(
                        placeholder = Placeholder(
                            width = width,
                            height = resolvedFontSize * 1.8f,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = numerator,
                                fontSize = resolvedFontSize * 0.66f,
                                lineHeight = resolvedFontSize * 0.72f,
                                fontWeight = FontWeight.Normal,
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                            Box(
                                modifier = Modifier.fillMaxWidth().height(0.8.dp)
                                    .background(color.copy(alpha = 0.85f))
                            )
                            Text(
                                text = denominator,
                                fontSize = resolvedFontSize * 0.66f,
                                lineHeight = resolvedFontSize * 0.72f,
                                fontWeight = FontWeight.Normal,
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                )
            }
        }
    }
    Text(
        text = annotated,
        inlineContent = inlineContent,
        modifier = modifier,
        style = style,
        color = color
    )
}

internal data class InlineFractionPart(
    val text: String,
    val numerator: String? = null,
    val denominator: String? = null
)

internal fun splitInlineFractions(source: String): List<InlineFractionPart> {
    val pattern = Regex("""(?<![\w./])(\d+)\s*/\s*(\d+)(?![\w./])""")
    val result = mutableListOf<InlineFractionPart>()
    var cursor = 0
    for (match in pattern.findAll(source)) {
        if (match.range.first > cursor) {
            result += InlineFractionPart(source.substring(cursor, match.range.first))
        }
        result += InlineFractionPart(
            text = match.value,
            numerator = match.groupValues[1],
            denominator = match.groupValues[2]
        )
        cursor = match.range.last + 1
    }
    if (result.isEmpty()) return listOf(InlineFractionPart(source))
    if (cursor < source.length) result += InlineFractionPart(source.substring(cursor))
    return result
}
