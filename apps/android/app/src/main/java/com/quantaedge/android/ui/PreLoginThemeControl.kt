package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Theme preference available before authentication on every signed-out account route. */
@Composable
internal fun PreLoginThemeControl() {
    val controller = LocalQuantaEdgeThemeController.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = when (controller.appearanceMode) {
        QeAppearanceMode.INSTITUTION -> localizedUiText("Default")
        QeAppearanceMode.SYSTEM -> localizedUiText("System")
        QeAppearanceMode.LIGHT -> localizedUiText("Light")
        QeAppearanceMode.DARK -> localizedUiText("Dark")
    }

    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.padding(end = 2.dp)) {
            Text(
                text = localizedUiText("Theme") + ": " + selectedLabel + "  ▾",
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(localizedUiText("Default")) },
                onClick = {
                    controller.updateAppearanceMode(QeAppearanceMode.INSTITUTION)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(localizedUiText("System")) },
                onClick = {
                    controller.updateAppearanceMode(QeAppearanceMode.SYSTEM)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(localizedUiText("Light")) },
                onClick = {
                    controller.updateAppearanceMode(QeAppearanceMode.LIGHT)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(localizedUiText("Dark")) },
                onClick = {
                    controller.updateAppearanceMode(QeAppearanceMode.DARK)
                    expanded = false
                }
            )
        }
    }
}
