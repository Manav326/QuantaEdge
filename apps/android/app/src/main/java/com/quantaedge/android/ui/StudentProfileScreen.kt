package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val schoolMediums = listOf("Hindi", "English", "Hindi & English", "Other")
private val favoriteSubjects = listOf("maths", "science", "both", "other", "not_sure")

private fun schoolMediumDisplay(value: String, language: String) = when (value) {
    "Hindi" -> if (language == "hi") "हिंदी" else "Hindi"
    "English" -> if (language == "hi") "अंग्रेज़ी" else "English"
    "Hindi & English" -> if (language == "hi") "हिंदी और अंग्रेज़ी" else "Hindi and English"
    else -> if (language == "hi") "अन्य" else "Other"
}
private fun favoriteSubjectDisplay(value: String, language: String) = when (value) {
    "maths" -> if (language == "hi") "गणित" else "Mathematics"
    "science" -> if (language == "hi") "विज्ञान" else "Science"
    "both" -> if (language == "hi") "दोनों" else "Both"
    "other" -> if (language == "hi") "अन्य" else "Other"
    else -> if (language == "hi") "अभी तय नहीं" else "Not sure yet"
}

@Composable
fun StudentProfileScreen(
    profile: JSONObject,
    language: String,
    busy: Boolean,
    onSave: (JSONObject) -> Unit,
    onUploadPhoto: (String) -> Unit,
    onRemovePhoto: () -> Unit,
    onToggleLanguage: () -> Unit,
    onLogout: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var displayName by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("") }
    var schoolName by rememberSaveable { mutableStateOf("") }
    var schoolMedium by rememberSaveable { mutableStateOf("Hindi") }
    var favoriteSubject by rememberSaveable { mutableStateOf("both") }
    var learningGoal by rememberSaveable { mutableStateOf("") }
    var learningLanguage by rememberSaveable { mutableStateOf("hi") }
    var schoolMediumExpanded by rememberSaveable { mutableStateOf(false) }
    var favoriteSubjectExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profile) {
        displayName = profile.textAny("display_name", "displayName")
        city = profile.textAny("city")
        state = profile.textAny("state")
        schoolName = profile.textAny("school_name", "schoolName")
        schoolMedium = profile.textAny("school_medium", "schoolMedium").ifBlank { "Hindi" }
        favoriteSubject = profile.textAny("favorite_subject", "favoriteSubject").ifBlank { "both" }
        learningGoal = profile.textAny("learning_goal", "learningGoal")
        learningLanguage = profile.textAny("language").trim().lowercase().let { if (it == "en") "en" else "hi" }
    }

    Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("Profile and learning preferences", "प्रोफ़ाइल और सीखने की पसंद"), style = MaterialTheme.typography.headlineSmall)
        ProfilePhotoPickerInput(
            profilePhotoUrl = profile.textAny("profile_image_url", "profileImageUrl"),
            busy = busy,
            onPhotoSelected = onUploadPhoto
        )
        if (profile.textAny("profile_image_url", "profileImageUrl").isNotBlank()) {
            TextButton(onClick = onRemovePhoto, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(t("Remove profile photo", "प्रोफ़ाइल फ़ोटो हटाएँ"))
            }
        }
        Text(t("Class", "कक्षा") + " " + profile.textAny("class_code", "classCode") + " • " + profile.textAny("board"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text(t("Name", "नाम")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it.take(100) },
            label = { Text(t("City or town (optional)", "शहर / कस्बा (वैकल्पिक)")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it.take(100) },
            label = { Text(t("State (optional)", "राज्य (वैकल्पिक)")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = schoolName, onValueChange = { schoolName = it.take(180) },
            label = { Text(t("School (optional)", "विद्यालय (वैकल्पिक)")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())

        Text(t("School medium", "विद्यालय की भाषा") + ": " + schoolMediumDisplay(schoolMedium, language),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { schoolMediumExpanded = true }, enabled = !busy) {
            Text(t("Select school medium", "विद्यालय की भाषा चुनें"))
        }
        DropdownMenu(expanded = schoolMediumExpanded, onDismissRequest = { schoolMediumExpanded = false }) {
            schoolMediums.forEach { option ->
                DropdownMenuItem(text = { Text(schoolMediumDisplay(option, language)) },
                    onClick = { schoolMedium = option; schoolMediumExpanded = false }, enabled = !busy)
            }
        }

        Text(t("Favourite subject", "पसंदीदा विषय") + ": " + favoriteSubjectDisplay(favoriteSubject, language),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { favoriteSubjectExpanded = true }, enabled = !busy) {
            Text(t("Select favourite subject", "पसंदीदा विषय चुनें"))
        }
        DropdownMenu(expanded = favoriteSubjectExpanded, onDismissRequest = { favoriteSubjectExpanded = false }) {
            favoriteSubjects.forEach { option ->
                DropdownMenuItem(text = { Text(favoriteSubjectDisplay(option, language)) },
                    onClick = { favoriteSubject = option; favoriteSubjectExpanded = false }, enabled = !busy)
            }
        }

        Text(t("Learning content language", "पढ़ाई की सामग्री की भाषा"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (learningLanguage == "hi") Button(onClick = { learningLanguage = "hi" }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Hindi", "हिंदी"))
            } else OutlinedButton(onClick = { learningLanguage = "hi" }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Hindi", "हिंदी"))
            }
            if (learningLanguage == "en") Button(onClick = { learningLanguage = "en" }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("English", "अंग्रेज़ी"))
            } else OutlinedButton(onClick = { learningLanguage = "en" }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("English", "अंग्रेज़ी"))
            }
        }

        OutlinedTextField(value = learningGoal, onValueChange = { learningGoal = it.take(300) },
            label = { Text(t("My learning goal", "मेरा सीखने का लक्ष्य")) }, minLines = 2, maxLines = 4,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            onSave(JSONObject()
                .put("displayName", displayName.trim())
                .put("city", city.trim())
                .put("state", state.trim())
                .put("schoolName", schoolName.trim())
                .put("schoolMedium", schoolMedium)
                .put("favoriteSubject", favoriteSubject)
                .put("learningGoal", learningGoal.trim())
                .put("language", learningLanguage))
        }, enabled = !busy && displayName.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text(t("Save profile", "प्रोफ़ाइल सुरक्षित करें"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onToggleLanguage, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (language == "hi") "EN" else "हिंदी")
            }
            TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Sign out", "साइन आउट"))
            }
        }
        Text(t("Learning materials stay inside the app. Protected downloads remain encrypted in private storage and do not offer sharing/export.",
            "पढ़ाई की सामग्री ऐप के अंदर उपलब्ध होती है। संरक्षित डाउनलोड encrypted private storage में रहते हैं और share/export विकल्प नहीं देते।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
