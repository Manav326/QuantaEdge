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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    onAccountLifecycle: () -> Unit,
    onLogout: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val clipboardManager = LocalClipboardManager.current
    val userId = profile.textAny("public_user_id", "publicUserId")
    var editing by rememberSaveable { mutableStateOf(false) }
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
    var profileDraftHydrated by rememberSaveable { mutableStateOf(false) }

    fun restoreProfileDraft() {
        displayName = profile.textAny("display_name", "displayName")
        city = profile.textAny("city")
        state = profile.textAny("state")
        schoolName = profile.textAny("school_name", "schoolName")
        schoolMedium = profile.textAny("school_medium", "schoolMedium").ifBlank { "Hindi" }
        favoriteSubject = profile.textAny("favorite_subject", "favoriteSubject").ifBlank { "both" }
        learningGoal = profile.textAny("learning_goal", "learningGoal")
        learningLanguage = profile.textAny("language").trim().lowercase().let { if (it == "en") "en" else "hi" }
        schoolMediumExpanded = false
        favoriteSubjectExpanded = false
        profileDraftHydrated = true
    }

    LaunchedEffect(profile) {
        if (!profileDraftHydrated) restoreProfileDraft()
    }

    Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t("Your profile", "आपकी प्रोफ़ाइल"), style = MaterialTheme.typography.headlineSmall)
                Text(t("Manage your personal details and learning preferences.", "अपनी जानकारी और पढ़ाई की पसंद अपडेट करें।"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!editing) {
                OutlinedButton(onClick = { restoreProfileDraft(); editing = true }, enabled = !busy) {
                    Text(t("Edit", "संपादित करें"))
                }
            }
        }
        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t("User ID", "यूज़र ID"), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(userId.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { if (userId.isNotBlank()) clipboardManager.setText(AnnotatedString(userId)) },
                        enabled = userId.isNotBlank()) {
                        Text(t("Copy ID", "ID कॉपी करें"))
                    }
                }
                Text(t("Class", "कक्षा") + " " + profile.textAny("class_code", "classCode") + " • " + profile.textAny("board"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (editing) {
            ProfilePhotoPickerInput(
                profilePhotoUrl = profile.textAny("profile_image_url", "profileImageUrl"),
                busy = busy,
                onPhotoSelected = onUploadPhoto
            )
            if (profile.textAny("profile_image_url", "profileImageUrl").isNotBlank()) {
                TextButton(onClick = onRemovePhoto, enabled = !busy && editing, modifier = Modifier.fillMaxWidth()) {
                    Text(t("Remove profile photo", "प्रोफ़ाइल फ़ोटो हटाएँ"))
                }
            }
        }
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text(t("Name", "नाम")) }, singleLine = true, enabled = !busy && editing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it.take(100) },
            label = { Text(t("City or town (optional)", "शहर / कस्बा (वैकल्पिक)")) }, singleLine = true, enabled = !busy && editing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it.take(100) },
            label = { Text(t("State (optional)", "राज्य (वैकल्पिक)")) }, singleLine = true, enabled = !busy && editing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = schoolName, onValueChange = { schoolName = it.take(180) },
            label = { Text(t("School (optional)", "विद्यालय (वैकल्पिक)")) }, singleLine = true, enabled = !busy && editing, modifier = Modifier.fillMaxWidth())

        Text(t("School medium", "विद्यालय की भाषा") + ": " + schoolMediumDisplay(schoolMedium, language),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { schoolMediumExpanded = true }, enabled = !busy && editing) {
            Text(t("Select school medium", "विद्यालय की भाषा चुनें"))
        }
        DropdownMenu(expanded = schoolMediumExpanded, onDismissRequest = { schoolMediumExpanded = false }) {
            schoolMediums.forEach { option ->
                DropdownMenuItem(text = { Text(schoolMediumDisplay(option, language)) },
                    onClick = { schoolMedium = option; schoolMediumExpanded = false }, enabled = !busy && editing)
            }
        }

        Text(t("Favourite subject", "पसंदीदा विषय") + ": " + favoriteSubjectDisplay(favoriteSubject, language),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { favoriteSubjectExpanded = true }, enabled = !busy && editing) {
            Text(t("Select favourite subject", "पसंदीदा विषय चुनें"))
        }
        DropdownMenu(expanded = favoriteSubjectExpanded, onDismissRequest = { favoriteSubjectExpanded = false }) {
            favoriteSubjects.forEach { option ->
                DropdownMenuItem(text = { Text(favoriteSubjectDisplay(option, language)) },
                    onClick = { favoriteSubject = option; favoriteSubjectExpanded = false }, enabled = !busy && editing)
            }
        }

        Text(t("Learning content language", "पढ़ाई की सामग्री की भाषा"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (learningLanguage == "hi") Button(onClick = { learningLanguage = "hi" }, enabled = !busy && editing, modifier = Modifier.weight(1f)) {
                Text(t("Hindi", "हिंदी"))
            } else OutlinedButton(onClick = { learningLanguage = "hi" }, enabled = !busy && editing, modifier = Modifier.weight(1f)) {
                Text(t("Hindi", "हिंदी"))
            }
            if (learningLanguage == "en") Button(onClick = { learningLanguage = "en" }, enabled = !busy && editing, modifier = Modifier.weight(1f)) {
                Text(t("English", "अंग्रेज़ी"))
            } else OutlinedButton(onClick = { learningLanguage = "en" }, enabled = !busy && editing, modifier = Modifier.weight(1f)) {
                Text(t("English", "अंग्रेज़ी"))
            }
        }

        OutlinedTextField(value = learningGoal, onValueChange = { learningGoal = it.take(300) },
            label = { Text(t("My learning goal", "मेरा सीखने का लक्ष्य")) }, minLines = 2, maxLines = 4,
            enabled = !busy && editing, modifier = Modifier.fillMaxWidth())
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { restoreProfileDraft(); editing = false },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(t("Cancel", "रद्द करें")) }
                Button(
                    onClick = {
                        onSave(JSONObject()
                            .put("displayName", displayName.trim())
                            .put("city", city.trim())
                            .put("state", state.trim())
                            .put("schoolName", schoolName.trim())
                            .put("schoolMedium", schoolMedium)
                            .put("favoriteSubject", favoriteSubject)
                            .put("learningGoal", learningGoal.trim())
                            .put("language", learningLanguage))
                        editing = false
                    },
                    enabled = !busy && displayName.trim().length >= 2,
                    modifier = Modifier.weight(1f)
                ) { Text(t("Save changes", "बदलाव सेव करें")) }
            }
        }
        OutlinedButton(onClick = onAccountLifecycle, enabled = !busy && editing, modifier = Modifier.fillMaxWidth()) {
            Text(t("Account & eligibility", "Account और eligibility"))
        }
        TextButton(onClick = onLogout, enabled = !busy && editing, modifier = Modifier.fillMaxWidth()) {
            Text(t("Sign out", "साइन आउट"))
        }
        Text(t("Your learning account and course eligibility.", "आपका learning account और course eligibility."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
