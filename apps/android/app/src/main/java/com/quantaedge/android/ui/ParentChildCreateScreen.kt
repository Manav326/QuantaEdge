package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable
fun ParentChildCreateScreen(
    busy: Boolean,
    remainingSlots: Int,
    onCreate: (String, String, String, String, String, List<String>, Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var classCode by rememberSaveable { mutableStateOf("7") }
    var learningLanguage by rememberSaveable { mutableStateOf("hi") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var maths by rememberSaveable { mutableStateOf(true) }
    var science by rememberSaveable { mutableStateOf(true) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val institutionBrand = LocalInstitutionBrandIdentity.current
    val institutionContextPending = LocalInstitutionBrandResolutionPending.current
    val tracks = listOfNotNull("maths".takeIf { maths }, "science".takeIf { science })

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localizedUiText("छात्र प्रोफ़ाइल बनाएँ"), style = MaterialTheme.typography.headlineSmall)
        Text(localizedUiText("Choose the learning language. Guardian consent is required."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        if (remainingSlots <= 0) {
            EmptyState(
                "छात्र प्रोफ़ाइल सीमा पूरी हो गई",
                if (institutionBrand != null || institutionContextPending) "और खाते बनाने के लिए अपने विद्यालय के प्रशासक से संपर्क करें।"
                else "और खाते बनाने के लिए support से संपर्क करें।"
            )
        } else {
            OutlinedTextField(value = name, onValueChange = { name = it.take(120) },
                label = { Text(localizedUiText("छात्र का पूरा नाम")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(localizedUiText("कक्षा"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("6", "7", "8").forEach { cls ->
                    if (classCode == cls) Button(onClick = { classCode = cls }, enabled = !busy) { Text(localizedUiText("कक्षा $cls")) }
                    else TextButton(onClick = { classCode = cls }, enabled = !busy) { Text(localizedUiText("कक्षा $cls")) }
                }
            }
            Text(localizedUiText("Learning language"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (learningLanguage == "hi") Button(onClick = { learningLanguage = "hi" }, enabled = !busy) { Text("हिन्दी") }
                else TextButton(onClick = { learningLanguage = "hi" }, enabled = !busy) { Text("हिन्दी") }
                if (learningLanguage == "en") Button(onClick = { learningLanguage = "en" }, enabled = !busy) { Text("English") }
                else TextButton(onClick = { learningLanguage = "en" }, enabled = !busy) { Text("English") }
            }
            Text(localizedUiText("पढ़ने के विषय"), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = maths, onCheckedChange = { maths = it }, enabled = !busy)
                Text(localizedUiText("गणित"))
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = science, onCheckedChange = { science = it }, enabled = !busy)
                Text(localizedUiText("विज्ञान"))
            }
            OutlinedTextField(value = username, onValueChange = { username = it.trim().lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" }.take(32) },
                label = { Text(localizedUiText("Login username (3–32 अक्षर)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it },
                label = { Text(localizedUiText("Login password (कम-से-कम 8 अक्षर)")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
                Text(localizedUiText("मैं अभिभावक हूँ और इस छात्र खाते के निर्माण व सीखने की प्रगति के लिए सहमति देता/देती हूँ।"))
            }
            Button(onClick = { onCreate(name.trim(), classCode, learningLanguage, username, password, tracks, consent) },
                enabled = !busy && name.trim().length >= 2 && username.length >= 3 && password.length >= 8 && tracks.isNotEmpty() && consent,
                modifier = Modifier.fillMaxWidth()) { Text(localizedUiText(if (busy) "बना रहे हैं…" else "सहमति के साथ छात्र बनाएँ")) }
        }
    }
}
