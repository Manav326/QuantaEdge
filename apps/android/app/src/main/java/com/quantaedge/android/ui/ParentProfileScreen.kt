package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ParentProfileScreen(
    profile: JSONObject,
    busy: Boolean,
    onSave: (JSONObject) -> Unit,
    onUploadPhoto: (String) -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var preferredLanguage by remember { mutableStateOf("Hindi") }

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
        Text("अभिभावक प्रोफ़ाइल", style = MaterialTheme.typography.headlineSmall)
        ProfilePhotoPickerInput(
            profilePhotoUrl = profile.textAny("profile_image_url", "profileImageUrl"),
            busy = busy,
            onPhotoSelected = onUploadPhoto
        )
        Text("मोबाइल: " + profile.textAny("mobile_e164"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text("नाम") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = email, onValueChange = { email = it.take(254) },
            label = { Text("ईमेल (वैकल्पिक)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it.take(100) },
            label = { Text("शहर / कस्बा") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it.take(100) },
            label = { Text("राज्य") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = occupation, onValueChange = { occupation = it.take(120) },
            label = { Text("पेशा") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = organization, onValueChange = { organization = it.take(180) },
            label = { Text("संस्था (वैकल्पिक)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("पसंदीदा भाषा: $preferredLanguage", style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.TextButton(onClick = {
            preferredLanguage = when (preferredLanguage) {
                "Hindi" -> "English"
                "English" -> "Hindi & English"
                "Hindi & English" -> "Other"
                else -> "Hindi"
            }
        }, enabled = !busy) { Text("भाषा बदलें") }
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
            Text("प्रोफ़ाइल सुरक्षित करें")
        }
    }
}
