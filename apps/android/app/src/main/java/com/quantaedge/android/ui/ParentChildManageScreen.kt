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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ParentChildManageScreen(
    child: JSONObject,
    busy: Boolean,
    onSaveProfile: (String, String, String) -> Unit,
    onSaveCredentials: (String, String) -> Unit,
    onSaveTracks: (List<String>) -> Unit,
    onArchive: () -> Unit
) {
    val childId = child.longAny("id")
    var displayName by rememberSaveable(childId) { mutableStateOf(child.textAny("display_name", "displayName")) }
    val existingClass = child.textAny("class_code").ifBlank { "6" }
    var classCode by rememberSaveable(childId) { mutableStateOf(existingClass) }
    var learningLanguage by rememberSaveable(childId) { mutableStateOf(child.textAny("language").ifBlank { "hi" }) }
    val classChoices = listOfNotNull(existingClass, when (existingClass) { "6" -> "7"; "7" -> "8"; else -> null })
    var username by rememberSaveable(childId) { mutableStateOf(child.textAny("login_username")) }
    var password by remember(childId) { mutableStateOf("") }
    val oldTracks = child.textAny("track_codes").split(',').map { it.trim() }.filter { it.isNotBlank() }
    var maths by rememberSaveable(childId) { mutableStateOf("maths" in oldTracks) }
    var science by rememberSaveable(childId) { mutableStateOf("science" in oldTracks) }
    var confirmArchive by rememberSaveable(childId) { mutableStateOf(false) }
    val tracks = listOfNotNull("maths".takeIf { maths }, "science".takeIf { science })

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(localizedUiText("छात्र प्रोफ़ाइल archive करें?")) },
            text = { Text(localizedUiText("यह छात्र खाते की access बंद कर देगा। यदि किसी दूसरे अभिभावक की active consent है, तो उनकी access backend के नियमों के अनुसार बनी रह सकती है।")) },
            confirmButton = {
                Button(onClick = { confirmArchive = false; onArchive() }, enabled = !busy) { Text(localizedUiText("Archive करें")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }, enabled = !busy) { Text(localizedUiText("रद्द करें")) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localizedUiText("Student profile"), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text(localizedUiText("Student name")) }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth())
        Text(localizedUiText("Class"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            classChoices.forEach { option ->
                if (classCode == option) Button(onClick = { classCode = option }, enabled = !busy) { Text(option) }
                else TextButton(onClick = { classCode = option }, enabled = !busy) { Text(option) }
            }
        }
        Text(localizedUiText("Learning language"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (learningLanguage == "hi") Button(onClick = { learningLanguage = "hi" }, enabled = !busy) { Text("हिन्दी") }
            else TextButton(onClick = { learningLanguage = "hi" }, enabled = !busy) { Text("हिन्दी") }
            if (learningLanguage == "en") Button(onClick = { learningLanguage = "en" }, enabled = !busy) { Text("English") }
            else TextButton(onClick = { learningLanguage = "en" }, enabled = !busy) { Text("English") }
        }
        Button(onClick = { onSaveProfile(displayName.trim(), classCode, learningLanguage) },
            enabled = !busy && displayName.trim().length >= 2 && classCode in classChoices,
            modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("Save profile")) }
        Text(localizedUiText("Login credentials"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = username, onValueChange = { username = it.trim().lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" }.take(32) },
            label = { Text(localizedUiText("Login username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text(localizedUiText("नया पासवर्ड (कम-से-कम 8 अक्षर)")) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSaveCredentials(username, password); password = "" },
            enabled = !busy && username.length >= 3 && password.length >= 8,
            modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("Credentials सुरक्षित करें")) }

        Text(localizedUiText("विषय access"), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = maths, onCheckedChange = { maths = it }, enabled = !busy)
            Text(localizedUiText("गणित"))
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = science, onCheckedChange = { science = it }, enabled = !busy)
            Text(localizedUiText("विज्ञान"))
        }
        Button(onClick = { onSaveTracks(tracks) }, enabled = !busy && tracks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("विषय परिवर्तन सुरक्षित करें")) }
        Text(localizedUiText("पासवर्ड सुरक्षा के लिए वर्तमान पासवर्ड ऐप में वापस नहीं दिखाया जाता।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        TextButton(onClick = { confirmArchive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(localizedUiText("छात्र प्रोफ़ाइल archive करें"), color = MaterialTheme.colorScheme.error)
        }
    }
}
