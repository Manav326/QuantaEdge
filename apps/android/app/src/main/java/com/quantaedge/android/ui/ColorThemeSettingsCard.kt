package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Appearance preference is available to every signed-in learner and parent via Profile & Settings. */
@Composable
fun ColorThemeSettingsCard(language: String) {
    val controller = LocalQuantaEdgeThemeController.current
    val selected = controller.appearanceMode
    fun label(en: String, hi: String) = if (language == "hi") hi else en

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                label("Colour appearance", "रंग रूप"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                label(
                    "Choose the institution theme, follow your device, or set light/dark mode. This changes colours only.",
                    "संस्था की थीम, डिवाइस की सेटिंग, या लाइट/डार्क मोड चुनें। इससे केवल रंग बदलते हैं।"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == QeAppearanceMode.INSTITUTION,
                    onClick = { controller.setAppearanceMode(QeAppearanceMode.INSTITUTION) },
                    label = { Text(label("Institution", "संस्था")) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selected == QeAppearanceMode.SYSTEM,
                    onClick = { controller.setAppearanceMode(QeAppearanceMode.SYSTEM) },
                    label = { Text(label("System", "डिवाइस")) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == QeAppearanceMode.LIGHT,
                    onClick = { controller.setAppearanceMode(QeAppearanceMode.LIGHT) },
                    label = { Text(label("Light", "लाइट")) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selected == QeAppearanceMode.DARK,
                    onClick = { controller.setAppearanceMode(QeAppearanceMode.DARK) },
                    label = { Text(label("Dark", "डार्क")) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
