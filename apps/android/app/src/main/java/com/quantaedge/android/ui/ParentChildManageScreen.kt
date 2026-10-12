package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun ParentChildManageScreen(
    child: JSONObject,
    busy: Boolean,
    onSaveCredentials: (String, String) -> Unit,
    onSaveTracks: (List<String>) -> Unit,
    onArchive: () -> Unit
) {
    val childId = child.longAny("id")
    var username by remember(childId) { mutableStateOf(child.textAny("login_username")) }
    var password by remember(childId) { mutableStateOf("") }
    val oldTracks = child.textAny("track_codes").split(',').map { it.trim() }.filter { it.isNotBlank() }
    var maths by remember(childId) { mutableStateOf("maths" in oldTracks) }
    var science by remember(childId) { mutableStateOf("science" in oldTracks) }
    var confirmArchive by remember(childId) { mutableStateOf(false) }
    val tracks = listOfNotNull("maths".takeIf { maths }, "science".takeIf { science })

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("छात्र प्रोफ़ाइल archive करें?") },
            text = { Text("यह छात्र खाते की access बंद कर देगा। यदि किसी दूसरे अभिभावक की active consent है, तो उनकी access backend के नियमों के अनुसार बनी रह सकती है।") },
            confirmButton = {
                Button(onClick = { confirmArchive = false; onArchive() }, enabled = !busy) { Text("Archive करें") }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }, enabled = !busy) { Text("रद्द करें") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("छात्र access", style = MaterialTheme.typography.headlineSmall)
        Text(child.textAny("display_name", "displayName"), style = MaterialTheme.typography.titleMedium)
        Text("कक्षा " + child.textAny("class_code") + " • Consent: " + child.textAny("consent_status"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text("Login credentials बदलें", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = username, onValueChange = { username = it.trim().lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" }.take(32) },
            label = { Text("Login username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("नया पासवर्ड (कम-से-कम 8 अक्षर)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSaveCredentials(username, password); password = "" },
            enabled = !busy && username.length >= 3 && password.length >= 8,
            modifier = Modifier.fillMaxWidth()) { Text("Credentials सुरक्षित करें") }

        Text("विषय access", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = maths, onCheckedChange = { maths = it }, enabled = !busy)
            Text("गणित")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = science, onCheckedChange = { science = it }, enabled = !busy)
            Text("विज्ञान")
        }
        Button(onClick = { onSaveTracks(tracks) }, enabled = !busy && tracks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) { Text("विषय परिवर्तन सुरक्षित करें") }
        Text("पासवर्ड सुरक्षा के लिए वर्तमान पासवर्ड ऐप में वापस नहीं दिखाया जाता।",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        TextButton(onClick = { confirmArchive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("छात्र प्रोफ़ाइल archive करें", color = MaterialTheme.colorScheme.error)
        }
    }
}
