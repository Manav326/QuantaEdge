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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val schoolMediums = listOf("Hindi", "English", "Hindi & English", "Other")
private val favoriteSubjects = listOf("maths", "science", "both", "other", "not_sure")

@Composable
fun StudentProfileScreen(
    profile: JSONObject,
    language: String,
    busy: Boolean,
    onSave: (JSONObject) -> Unit,
    onUploadPhoto: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onLogout: () -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var schoolName by remember { mutableStateOf("") }
    var schoolMedium by remember { mutableStateOf("Hindi") }
    var favoriteSubject by remember { mutableStateOf("both") }
    var learningGoal by remember { mutableStateOf("") }

    LaunchedEffect(profile) {
        displayName = profile.textAny("display_name", "displayName")
        city = profile.textAny("city")
        state = profile.textAny("state")
        schoolName = profile.textAny("school_name", "schoolName")
        schoolMedium = profile.textAny("school_medium", "schoolMedium").ifBlank { "Hindi" }
        favoriteSubject = profile.textAny("favorite_subject", "favoriteSubject").ifBlank { "both" }
        learningGoal = profile.textAny("learning_goal", "learningGoal")
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("प्रोफ़ाइल और सीखने की पसंद", style = MaterialTheme.typography.headlineSmall)
        ProfilePhotoPickerInput(
            profilePhotoUrl = profile.textAny("profile_image_url", "profileImageUrl"),
            busy = busy,
            onPhotoSelected = onUploadPhoto
        )
        Text("कक्षा " + profile.textAny("class_code", "classCode") + " • " + profile.textAny("board"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text("नाम") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it.take(100) },
            label = { Text("शहर / कस्बा (वैकल्पिक)") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it.take(100) },
            label = { Text("राज्य (वैकल्पिक)") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = schoolName, onValueChange = { schoolName = it.take(180) },
            label = { Text("विद्यालय (वैकल्पिक)") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("School medium: $schoolMedium", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            schoolMedium = schoolMediums[(schoolMediums.indexOf(schoolMedium).coerceAtLeast(0) + 1) % schoolMediums.size]
        }, enabled = !busy) { Text("School medium बदलें") }
        Text("पसंदीदा विषय: $favoriteSubject", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            favoriteSubject = favoriteSubjects[(favoriteSubjects.indexOf(favoriteSubject).coerceAtLeast(0) + 1) % favoriteSubjects.size]
        }, enabled = !busy) { Text("पसंदीदा विषय बदलें") }
        OutlinedTextField(value = learningGoal, onValueChange = { learningGoal = it.take(300) },
            label = { Text("मेरा सीखने का लक्ष्य") }, minLines = 2, maxLines = 4, enabled = !busy,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            onSave(JSONObject()
                .put("displayName", displayName.trim())
                .put("city", city.trim())
                .put("state", state.trim())
                .put("schoolName", schoolName.trim())
                .put("schoolMedium", schoolMedium)
                .put("favoriteSubject", favoriteSubject)
                .put("learningGoal", learningGoal.trim()))
        }, enabled = !busy && displayName.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text("प्रोफ़ाइल सुरक्षित करें")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onToggleLanguage, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (language == "hi") "Switch to English" else "हिंदी में बदलें")
            }
            TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.weight(1f)) { Text("साइन आउट") }
        }
        Text("पढ़ाई की सामग्री ऐप के अंदर उपलब्ध होती है। संरक्षित डाउनलोड encrypted private storage में रहते हैं और share/export विकल्प नहीं देते।",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
