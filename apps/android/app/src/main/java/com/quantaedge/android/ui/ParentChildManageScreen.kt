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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ParentChildManageScreen(
    child: JSONObject,
    courses: List<JSONObject>,
    language: String,
    busy: Boolean,
    onSaveProfile: (String, String, String) -> Unit,
    onSaveCredentials: (String, String) -> Unit,
    onAssignCourse: (Long) -> Unit,
    onOpenMarketplace: () -> Unit,
    onArchive: () -> Unit
) {
    val childId = child.longAny("id")
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var displayName by rememberSaveable(childId) { mutableStateOf(child.textAny("display_name", "displayName")) }
    val existingClass = child.textAny("class_code").ifBlank { "6" }
    var classCode by rememberSaveable(childId) { mutableStateOf(existingClass) }
    var learningLanguage by rememberSaveable(childId) { mutableStateOf(child.textAny("language").ifBlank { "hi" }) }
    // Match the API's allowed promotion lifecycle: grade 6 → 7 → 8; no demotion or skipping.
    val classChoices = listOf(existingClass) + when (existingClass) {
        "6" -> listOf("7")
        "7" -> listOf("8")
        else -> emptyList()
    }
    var username by rememberSaveable(childId) { mutableStateOf(child.textAny("login_username")) }
    var password by remember(childId) { mutableStateOf("") }
    var confirmArchive by rememberSaveable(childId) { mutableStateOf(false) }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(t("Archive this student profile?", "छात्र प्रोफ़ाइल archive करें?")) },
            text = { Text(t(
                "This disables the student account. Learning history and course enrolments are retained; another guardian's access may remain active under the server's consent rules.",
                "इससे छात्र खाते में sign-in बंद हो जाएगा। पढ़ाई का इतिहास और course enrolments सुरक्षित रहेंगे; दूसरे अभिभावक की स्वीकृत access बनी रह सकती है।"
            )) },
            confirmButton = {
                Button(onClick = { confirmArchive = false; onArchive() }, enabled = !busy) {
                    Text(t("Archive", "Archive करें"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }, enabled = !busy) {
                    Text(t("Cancel", "रद्द करें"))
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(t("Student profile", "छात्र प्रोफ़ाइल"), style = MaterialTheme.typography.headlineSmall)
        Text(t(
            "Profile and sign-in details are managed here. Learning access is controlled by institution course enrolments below.",
            "यहाँ प्रोफ़ाइल और लॉगिन विवरण प्रबंधित करें। पढ़ाई की access नीचे दिए गए संस्थान के course enrolments से नियंत्रित होती है।"
        ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

        OutlinedTextField(
            value = displayName, onValueChange = { displayName = it.take(120) },
            label = { Text(t("Student name", "छात्र का नाम")) }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Text(t("Grade / class identity", "कक्षा / स्तर"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            classChoices.forEach { option ->
                if (classCode == option) Button(onClick = { classCode = option }, enabled = !busy) {
                    Text(t("Class ", "कक्षा ") + option)
                } else TextButton(onClick = { classCode = option }, enabled = !busy) {
                    Text(t("Class ", "कक्षा ") + option)
                }
            }
        }
        Text(t("Learning language", "सीखने की भाषा"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (learningLanguage == "hi") Button(onClick = { learningLanguage = "hi" }, enabled = !busy) {
                Text("हिन्दी")
            } else TextButton(onClick = { learningLanguage = "hi" }, enabled = !busy) { Text("हिन्दी") }
            if (learningLanguage == "en") Button(onClick = { learningLanguage = "en" }, enabled = !busy) {
                Text("English")
            } else TextButton(onClick = { learningLanguage = "en" }, enabled = !busy) { Text("English") }
        }
        Button(
            onClick = { onSaveProfile(displayName.trim(), classCode, learningLanguage) },
            enabled = !busy && displayName.trim().length >= 2 && classCode in classChoices,
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Save profile", "प्रोफ़ाइल सुरक्षित करें")) }

        Text(t("Student login credentials", "छात्र के लॉगिन विवरण"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim().lowercase().filter { ch -> ch.isLetterOrDigit() || ch in "._-" }.take(32) },
            label = { Text(t("Login username", "लॉगिन यूज़रनेम")) }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(t("New password (at least 8 characters)", "नया पासवर्ड (कम-से-कम 8 अक्षर)")) },
            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSaveCredentials(username, password); password = "" },
            enabled = !busy && username.length >= 3 && password.length >= 8,
            modifier = Modifier.fillMaxWidth()
        ) { Text(t("Save credentials", "लॉगिन विवरण सुरक्षित करें")) }

        Text(t("Institution courses", "संस्थान के courses"), style = MaterialTheme.typography.titleMedium)
        Text(t(
            "Subjects are determined by each course's approved mapping. Assign a free course here, or use Family Marketplace for paid courses.",
            "हर course के स्वीकृत mapping से उसके विषय तय होते हैं। यहाँ free course assign करें या paid course के लिए Family Marketplace खोलें।"
        ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

        if (courses.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("No institution course options are available", "संस्थान के course विकल्प उपलब्ध नहीं हैं"))
                    Text(t("Retry from the profile screen or browse the Family Marketplace.", "प्रोफ़ाइल स्क्रीन से फिर प्रयास करें या Family Marketplace देखें।"),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpenMarketplace, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Open Family Marketplace", "Family Marketplace खोलें"))
                    }
                }
            }
        } else {
            courses.forEach { course ->
                val courseId = course.longAny("id", "course_id")
                val enrolled = course.optBoolean("enrolled") || course.optBoolean("paused")
                val freeOffer = course.optBoolean("free_offer")
                val courseName = course.textAny("display_name", "course_name", "code").ifBlank {
                    t("Course ", "Course ") + courseId
                }
                val subjects = course.optJSONArray("subjects")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { subject ->
                            subject.textAny("subject_name", "subject_code", "code").takeIf { it.isNotBlank() }
                        }
                    }
                }.orEmpty()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(courseName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            course.textAny("public_id", "code") +
                                (if (course.textAny("curriculum_version").isNotBlank()) " · " + course.textAny("curriculum_version") else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (subjects.isNotEmpty()) Text(
                            subjects.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when {
                            enrolled -> Text(t("Assigned to this child", "इस बच्चे को assigned"),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            freeOffer -> Button(
                                onClick = { if (courseId > 0L) onAssignCourse(courseId) },
                                enabled = !busy && courseId > 0L,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(t("Assign free course", "Free course assign करें")) }
                            else -> OutlinedButton(
                                onClick = onOpenMarketplace, enabled = !busy, modifier = Modifier.fillMaxWidth()
                            ) { Text(t("Get course in Family Marketplace", "Family Marketplace से course लें")) }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onOpenMarketplace, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(t("Browse Family Marketplace", "Family Marketplace देखें"))
        }
        Text(t(
            "For security, the current password is never shown again in the app.",
            "सुरक्षा के लिए वर्तमान पासवर्ड ऐप में वापस नहीं दिखाया जाता।"
        ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        TextButton(onClick = { confirmArchive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(t("Archive student profile", "छात्र प्रोफ़ाइल archive करें"), color = MaterialTheme.colorScheme.error)
        }
    }
}
