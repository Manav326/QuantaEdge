package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ParentArchivedChildrenScreen(
    children: List<JSONObject>,
    busy: Boolean,
    onRestore: (JSONObject) -> Unit
) {
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    selected?.let { child ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(localizedUiText("Restore profile?")) },
            text = { Text(localizedUiText("Fresh consent is required. Old login credentials will be disabled; learning history stays intact.")) },
            confirmButton = {
                Button(onClick = { selected = null; onRestore(child) }, enabled = !busy) {
                    Text(localizedUiText("Confirm restore"))
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }, enabled = !busy) { Text(localizedUiText("Cancel")) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedUiText("Archived profiles"), style = MaterialTheme.typography.headlineSmall)
        Text(localizedUiText("Restore requires fresh consent and new student login credentials."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        if (children.isEmpty()) {
            EmptyState(localizedUiText("No archived profiles"), localizedUiText("Archived children appear here."))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(children) { child ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(child.textAny("display_name", "displayName").ifBlank { "Student" },
                                style = MaterialTheme.typography.titleMedium)
                            Text(localizedUiText("Class ") + child.textAny("class_code", "class_name"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            OutlinedButton(onClick = { selected = child }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("Restore")) }
                        }
                    }
                }
            }
        }
    }
}
