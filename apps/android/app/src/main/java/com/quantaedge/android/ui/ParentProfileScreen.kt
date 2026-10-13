package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private fun preferredLanguageLabel(value: String, language: String): String = when (value) {
    "Hindi" -> if (language == "hi") "हिंदी" else "Hindi"
    "English" -> if (language == "hi") "अंग्रेज़ी" else "English"
    "Hindi & English" -> if (language == "hi") "हिंदी और अंग्रेज़ी" else "Hindi and English"
    else -> if (language == "hi") "अन्य" else "Other"
}

@Composable
fun ParentProfileScreen(
    profile: JSONObject,
    language: String,
    busy: Boolean,
    onSave: (JSONObject) -> Unit,
    onUploadPhoto: (String) -> Unit,
    onRemovePhoto: () -> Unit
) {
    val context = LocalContext.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var preferredLanguage by remember { mutableStateOf("Hindi") }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        displayName = profile.textAny("display_name", "displayName")
        email = profile.textAny("email")
        city = profile.textAny("city")
        state = profile.textAny("state")
        occupation = profile.textAny("occupation")
        organization = profile.textAny("organization")
        preferredLanguage = profile.textAny("preferred_language", "preferredLanguage").ifBlank { "Hindi" }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("Parent profile", "अभिभावक प्रोफ़ाइल"), style = MaterialTheme.typography.headlineSmall)
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
        Text(t("Mobile", "मोबाइल") + ": " + profile.textAny("mobile_e164"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text(t("Name", "नाम")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = email, onValueChange = { email = it.take(254) },
            label = { Text(t("Email (optional)", "ईमेल (वैकल्पिक)")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it.take(100) },
            label = { Text(t("City or town", "शहर / कस्बा")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it.take(100) },
            label = { Text(t("State", "राज्य")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = occupation, onValueChange = { occupation = it.take(120) },
            label = { Text(t("Occupation", "पेशा")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = organization, onValueChange = { organization = it.take(180) },
            label = { Text(t("Organization (optional)", "संस्था (वैकल्पिक)")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())

        Text(t("Preferred learning language", "पढ़ाई की पसंदीदा भाषा") + ": " +
            preferredLanguageLabel(preferredLanguage, language), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { languageMenuExpanded = true }, enabled = !busy) {
            Text(t("Select language", "भाषा चुनें"))
        }
        DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
            listOf("Hindi", "English", "Hindi & English", "Other").forEach { option ->
                DropdownMenuItem(
                    text = { Text(preferredLanguageLabel(option, language)) },
                    onClick = { preferredLanguage = option; languageMenuExpanded = false },
                    enabled = !busy
                )
            }
        }

        Button(onClick = {
            onSave(JSONObject()
                .put("displayName", displayName.trim())
                .put("email", email.trim())
                .put("city", city.trim())
                .put("state", state.trim())
                .put("occupation", occupation.trim())
                .put("organization", organization.trim())
                .put("preferredLanguage", preferredLanguage))
        }, enabled = !busy && displayName.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text(t("Save profile", "प्रोफ़ाइल सुरक्षित करें"))
        }
        Text(t("Your preferred learning language is a profile preference; it does not change the app interface language.",
            "पढ़ाई की पसंदीदा भाषा प्रोफ़ाइल की सेटिंग है; इससे ऐप की इंटरफ़ेस भाषा नहीं बदलती।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
