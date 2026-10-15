package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    language: String,
    onToggleLanguage: () -> Unit,
    onStudentLogin: () -> Unit,
    onParentLogin: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit
) {
    val hi = language == "hi"
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggleLanguage) { Text(if (hi) "English" else "हिन्दी") }
            }
            QuantaEdgeBrand(modifier = Modifier.fillMaxWidth(), compact = false, centered = true)
            Spacer(Modifier.height(14.dp))
            Text(if (hi) "कक्षा 6–8 · Bihar Board" else "Classes 6–8 · Bihar Board",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(if (hi) "पढ़ाई, बच्चे के हिसाब से।" else "Learning, adapted to each child.",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (hi) "Concept समझें, practice करें और progress देखें।"
                else "Understand concepts, practise, and track progress.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStudentLogin, modifier = Modifier.fillMaxWidth()) {
                Text(if (hi) "पढ़ाई शुरू करें →" else "Start learning →")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onParentLogin, modifier = Modifier.fillMaxWidth()) {
                Text(if (hi) "मैं Parent हूँ" else "I'm a parent")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrivacy) { Text("Privacy") }
                TextButton(onClick = onTerms) { Text("Terms") }
            }
        }
    }
}
