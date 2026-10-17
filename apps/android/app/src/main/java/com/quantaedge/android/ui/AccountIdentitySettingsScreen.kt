package com.quantaedge.android.ui

import android.content.Context
import org.json.JSONObject
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AccountIdentitySettingsScreen(
    profile: JSONObject,
    profileImage: ImageBitmap?,
    language: String,
    busy: Boolean,
    error: String,
    status: String,
    onRefresh: () -> Unit,
    onSaveProfile: (String, String) -> Unit,
    onSaveUsername: (String) -> Unit,
    onSavePassword: (String, String) -> Unit,
    onLinkGmail: (Context) -> Unit,
    onAccountLifecycle: () -> Unit,
    onBack: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val context = LocalContext.current
    val displayNameFromApi = profile.optString("displayName", "")
    val usernameFromApi = profile.optString("username", "")
    val preferredLocaleFromApi = profile.optString("preferredLocale", "hinglish")
    var displayName by rememberSaveable(displayNameFromApi) { mutableStateOf(displayNameFromApi) }
    var username by rememberSaveable(usernameFromApi) { mutableStateOf(usernameFromApi) }
    var preferredLocale by rememberSaveable(preferredLocaleFromApi) { mutableStateOf(preferredLocaleFromApi) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    val accountType = profile.optString("accountType", "USER")
    val childManaged = profile.optBoolean("credentialsManagedByParent")
    val gmailVerified = profile.optBoolean("gmailVerified")
    val mobileVerified = profile.optBoolean("mobileVerified")
    val passwordSet = profile.optBoolean("passwordSet")
    val usernameCanChange = profile.optBoolean("usernameCanChange", usernameFromApi.isBlank())
    val nextUsernameDate = profile.optString("usernameCanChangeAt", "")
    val verifiedMethods = profile.optJSONArray("verifiedMethods")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(t("ACCOUNT & SIGN-IN", "खाता और साइन-इन"),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)
                Text(t("Account identity", "खाते की पहचान"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onBack, enabled = !busy) { Text(t("Back", "वापस")) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (profileImage != null) {
                Image(
                    bitmap = profileImage,
                    contentDescription = t("Profile picture", "प्रोफ़ाइल चित्र"),
                    modifier = Modifier.size(68.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(68.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(displayName.firstOrNull()?.uppercase() ?: "Q",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayName.ifBlank { t("Your name", "आपका नाम") },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (accountType) {
                        "PARENT" -> t("Parent account", "अभिभावक खाता")
                        "CHILD" -> t("Young Learner · Parent-managed", "बाल शिक्षार्थी · अभिभावक द्वारा प्रबंधित")
                        else -> t("Regular learner account", "सामान्य learner खाता")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Verification status", "सत्यापन स्थिति"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(localizedUiText("Gmail"), fontWeight = FontWeight.SemiBold)
                        Text(if (gmailVerified) profile.optString("email") else t("No verified Gmail connected", "कोई सत्यापित Gmail जुड़ा नहीं है"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (gmailVerified) "✓ " + t("Verified", "सत्यापित") else t("Not verified", "सत्यापित नहीं"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (gmailVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Mobile number", "मोबाइल नंबर"), fontWeight = FontWeight.SemiBold)
                        Text(if (mobileVerified) profile.optString("mobile")
                            else t("No verified mobile linked", "कोई सत्यापित मोबाइल लिंक नहीं है"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (mobileVerified) "✓ " + t("Verified", "सत्यापित") else t("Not verified", "सत्यापित नहीं"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mobileVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text(t(
                    "Gmail is verified only by Google. A mobile number is verified only after OTP. Profile name and picture do not verify a mobile number.",
                    "Gmail केवल Google से सत्यापित होता है। मोबाइल नंबर OTP के बाद ही सत्यापित होता है। प्रोफ़ाइल नाम या तस्वीर मोबाइल सत्यापित नहीं करते।"
                ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!gmailVerified && !childManaged) {
                    OutlinedButton(onClick = { onLinkGmail(context) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Connect verified Gmail", "सत्यापित Gmail जोड़ें"))
                    }
                }
            }
        }

        if (childManaged) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("Parent-managed learner account", "अभिभावक द्वारा प्रबंधित learner खाता"),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(t(
                        "Your parent controls your username and password. Ask them to change your credentials from Child accounts.",
                        "आपका यूज़रनेम और पासवर्ड अभिभावक नियंत्रित करते हैं। उनसे Child accounts में विवरण बदलने को कहें।"
                    ), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Profile details", "प्रोफ़ाइल विवरण"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(120) },
                        label = { Text(t("Full name", "पूरा नाम")) }, singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth())
                    Text(t("Interface language", "इंटरफ़ेस भाषा"), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("hinglish" to "हिन्दी", "english" to "English", "bhojpuri" to "भोजपुरी").forEach { (id, label) ->
                            if (preferredLocale.equals(id, true)) Button(onClick = { preferredLocale = id }, enabled = !busy,
                                modifier = Modifier.weight(1f)) { Text(label) }
                            else OutlinedButton(onClick = { preferredLocale = id }, enabled = !busy,
                                modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                    Button(onClick = { onSaveProfile(displayName.trim(), preferredLocale) },
                        enabled = !busy && displayName.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Save profile", "प्रोफ़ाइल सुरक्षित करें"))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Username", "यूज़रनेम"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = username, onValueChange = {
                        username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch in "._-" }.take(32)
                    }, label = { Text(t("3–32 characters", "3–32 अक्षर")) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (!usernameCanChange && nextUsernameDate.isNotBlank()) Text(
                        t("Next username change time: ", "अगला यूज़रनेम बदलाव: ") + nextUsernameDate,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("A changed username is locked for 15 days. Usernames must be unique.",
                        "बदला हुआ यूज़रनेम 15 दिनों तक लॉक रहेगा। यूज़रनेम अलग होना चाहिए।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onSaveUsername(username.trim()) },
                        enabled = !busy && usernameCanChange && username.trim().length >= 3 && username.trim() != usernameFromApi,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (usernameFromApi.isBlank()) t("Set username", "यूज़रनेम सेट करें") else t("Save username", "यूज़रनेम सुरक्षित करें"))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (passwordSet) t("Change password", "पासवर्ड बदलें") else t("Set password", "पासवर्ड सेट करें"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (passwordSet) OutlinedTextField(value = currentPassword, onValueChange = { currentPassword = it },
                        label = { Text(t("Current password", "मौजूदा पासवर्ड")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPassword, onValueChange = { newPassword = it },
                        label = { Text(t("New password (8+ characters)", "नया पासवर्ड (कम से कम 8 अक्षर)")) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it },
                        label = { Text(t("Confirm new password", "नए पासवर्ड की पुष्टि करें")) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onSavePassword(currentPassword, newPassword) },
                        enabled = !busy && newPassword.length >= 8 && newPassword == confirmPassword &&
                            (!passwordSet || currentPassword.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                        Text(t("Save password", "पासवर्ड सुरक्षित करें"))
                    }
                }
            }
        }

        if (profile.optBoolean("canRequestParentAccount") && accountType != "PARENT") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Become a parent", "अभिभावक खाता बनाएँ"),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(t(
                        "Parent access requires adult verification and eligibility review. Account type cannot be changed by editing the profile.",
                        "अभिभावक पहुँच के लिए वयस्कता सत्यापन और पात्रता समीक्षा आवश्यक है। प्रोफ़ाइल बदलकर account type नहीं बदला जा सकता।"
                    ), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onAccountLifecycle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Review eligibility", "पात्रता देखें"))
                    }
                }
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(t("Refresh account status", "खाते की स्थिति रीफ़्रेश करें"))
        }
    }
}
