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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.input.VisualTransformation
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
    deletionRequest: JSONObject,
    deletionVerificationId: String,
    deletionOtpDevCode: String,
    onRefreshDeletion: () -> Unit,
    onRequestDeletionOtp: () -> Unit,
    onVerifyDeletionOtp: (String) -> Unit,
    onRequestDeletion: (String, String, String, Boolean) -> Unit,
    onRequestChildDeletion: (String, String, String, String, Boolean) -> Unit,
    onCancelDeletion: () -> Unit,
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
    var currentPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var newPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var deletionConfirmation by rememberSaveable { mutableStateOf("") }
    var deletionReasonCategory by rememberSaveable { mutableStateOf("") }
    var deletionReasonDetails by rememberSaveable { mutableStateOf("") }
    var deletionOtp by rememberSaveable { mutableStateOf("") }
    var deletionOtpRequested by rememberSaveable { mutableStateOf(false) }
    var deletionManualIdentityReview by rememberSaveable { mutableStateOf(false) }
    var deletionReasonMenuExpanded by rememberSaveable { mutableStateOf(false) }
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
                    color = LocalQuantaEdgeSemanticColors.current.primaryText, style = MaterialTheme.typography.labelMedium,
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
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)


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
                        color = if (gmailVerified) LocalQuantaEdgeSemanticColors.current.success else MaterialTheme.colorScheme.error)
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
                        color = if (mobileVerified) LocalQuantaEdgeSemanticColors.current.success else MaterialTheme.colorScheme.error)
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
                        visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(), enabled = !busy,
                        trailingIcon = { PasswordVisibilityToggle(currentPasswordVisible, { currentPasswordVisible = !currentPasswordVisible }, t(if (currentPasswordVisible) "Hide password" else "Show password", if (currentPasswordVisible) "पासवर्ड छिपाएँ" else "पासवर्ड दिखाएँ")) },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPassword, onValueChange = { newPassword = it },
                        label = { Text(t("New password (8+ characters)", "नया पासवर्ड (कम से कम 8 अक्षर)")) },
                        singleLine = true, visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(), enabled = !busy,
                        trailingIcon = { PasswordVisibilityToggle(newPasswordVisible, { newPasswordVisible = !newPasswordVisible }, t(if (newPasswordVisible) "Hide password" else "Show password", if (newPasswordVisible) "पासवर्ड छिपाएँ" else "पासवर्ड दिखाएँ")) },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it },
                        label = { Text(t("Confirm new password", "नए पासवर्ड की पुष्टि करें")) },
                        singleLine = true, visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(), enabled = !busy,
                        trailingIcon = { PasswordVisibilityToggle(confirmPasswordVisible, { confirmPasswordVisible = !confirmPasswordVisible }, t(if (confirmPasswordVisible) "Hide password" else "Show password", if (confirmPasswordVisible) "पासवर्ड छिपाएँ" else "पासवर्ड दिखाएँ")) },
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onSavePassword(currentPassword, newPassword) },
                        enabled = !busy && newPassword.length >= 8 && newPassword == confirmPassword &&
                            (!passwordSet || currentPassword.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                        Text(t("Save password", "पासवर्ड सुरक्षित करें"))
                    }
                }
            }
        }

            val requestExists = deletionRequest.optBoolean("hasRequest")
            val requestStatus = deletionRequest.optString("status", "NONE")
            val requestIsOpen = requestStatus in setOf("REQUESTED", "UNDER_REVIEW", "AWAITING_LINKED_CHILD_DECISION")
            val requestStatusLabel = when (requestStatus) {
                "REQUESTED" -> localizedUiText("Request received")
                "UNDER_REVIEW" -> localizedUiText("Under review")
                "AWAITING_LINKED_CHILD_DECISION" -> localizedUiText("Linked-child decision required")
                "CANCELLED" -> localizedUiText("Request cancelled")
                "COMPLETED" -> localizedUiText("Deletion completed")
                "REJECTED" -> localizedUiText("Support is needed for this request")
                else -> localizedUiText("No deletion request")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(localizedUiText("Delete your account"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(localizedUiText("Submit a request to delete your account and associated personal data. Submitting a request does not mean deletion is complete."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(localizedUiText("Payment, invoice, certificate and security records may be retained only where there is a justified legal reason."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (requestExists) {
                        Text(localizedUiText("Request status: ") + requestStatusLabel,
                            style = MaterialTheme.typography.titleSmall)
                        if (deletionRequest.optInt("linkedChildrenCount", 0) > 0) {
                            val childCount = deletionRequest.optInt("linkedChildrenCount", 0)
                            val childrenMode = if (deletionRequest.optBoolean("includesLinkedChildren")) {
                                localizedUiText("Included in this deletion request")
                            } else {
                                localizedUiText("Not included in this request")
                            }
                            Text(localizedUiText("Linked child accounts: ") + childCount + ". " + childrenMode + ".",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (requestIsOpen) {
                            Text(localizedUiText("Your request is recorded but processing has not completed. You can cancel it while it is still open."),
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                deletionConfirmation = ""
                                onCancelDeletion()
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(localizedUiText("Cancel deletion request"))
                            }
                        }
                        if (requestStatus == "CANCELLED" || requestStatus == "REJECTED") {
                            Text(localizedUiText("You can submit a new request after reviewing the current status."),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (!requestExists || requestStatus == "CANCELLED" || requestStatus == "REJECTED") {
                        Text(localizedUiText("Before submitting an account-deletion request, tell us why you want to delete the account."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            OutlinedButton(onClick = { deletionReasonMenuExpanded = true }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                val reasonLabel = when (deletionReasonCategory) {
                                    "NO_LONGER_NEEDED" -> localizedUiText("I no longer need this account")
                                    "PRIVACY_CONCERN" -> localizedUiText("Privacy concerns")
                                    "SECURITY_CONCERN" -> localizedUiText("Account security concern")
                                    "DUPLICATE_ACCOUNT" -> localizedUiText("I have a duplicate account")
                                    "MOVING_TO_ANOTHER_SERVICE" -> localizedUiText("I am moving to another service")
                                    "DATA_CONCERN" -> localizedUiText("Concern about my personal data")
                                    "OTHER" -> localizedUiText("Other reason")
                                    else -> localizedUiText("Choose a reason")
                                }
                                Text(reasonLabel)
                            }
                            DropdownMenu(expanded = deletionReasonMenuExpanded,
                                onDismissRequest = { deletionReasonMenuExpanded = false }) {
                                listOf(
                                    "NO_LONGER_NEEDED" to "I no longer need this account",
                                    "PRIVACY_CONCERN" to "Privacy concerns",
                                    "SECURITY_CONCERN" to "Account security concern",
                                    "DUPLICATE_ACCOUNT" to "I have a duplicate account",
                                    "MOVING_TO_ANOTHER_SERVICE" to "I am moving to another service",
                                    "DATA_CONCERN" to "Concern about my personal data",
                                    "OTHER" to "Other reason"
                                ).forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(localizedUiText(label)) },
                                        onClick = {
                                            deletionReasonCategory = key
                                            deletionReasonMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = deletionReasonDetails,
                            onValueChange = { deletionReasonDetails = it.take(1500) },
                            label = { Text(localizedUiText("Additional explanation (optional; required for Other)")) },
                            placeholder = { Text(localizedUiText("Share a brief explanation, without passwords or OTPs")) },
                            enabled = !busy,
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (deletionReasonCategory == "OTHER" && deletionReasonDetails.trim().length < 5) {
                            Text(localizedUiText("Please add a short explanation when selecting Other."),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        if (mobileVerified) {
                            Text(localizedUiText("Verify your identity before submitting or requesting deletion of a linked child account."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                deletionOtp = ""
                                deletionOtpRequested = true
                                onRequestDeletionOtp()
                            }, enabled = !busy && deletionVerificationId.isBlank(),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(localizedUiText("Send verification code to your verified mobile"))
                            }
                            if (deletionOtpDevCode.isNotBlank()) {
                                Text(localizedUiText("Development OTP: ") + deletionOtpDevCode,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (deletionOtpRequested && deletionVerificationId.isBlank()) {
                                OutlinedTextField(
                                    value = deletionOtp,
                                    onValueChange = { deletionOtp = it.filter(Char::isDigit).take(6) },
                                    label = { Text(localizedUiText("Enter the 6-digit code")) },
                                    singleLine = true,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(onClick = { onVerifyDeletionOtp(deletionOtp) },
                                    enabled = !busy && deletionOtp.length == 6,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(localizedUiText("Verify code"))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deletionManualIdentityReview, enabled = !busy,
                                onCheckedChange = { deletionManualIdentityReview = it })
                            Text(localizedUiText("Ask staff to verify my identity manually"))
                        }
                        if (deletionManualIdentityReview) {
                            Text(localizedUiText("This only requests manual identity review; it does not mark identity as verified or complete deletion."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (deletionVerificationId.isNotBlank() && !deletionManualIdentityReview) {
                            Text(localizedUiText("Identity verification complete."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        val childArray = deletionRequest.optJSONArray("linkedChildren")
                        val childCount = deletionRequest.optInt("linkedChildrenCount", childArray?.length() ?: 0)
                        if (childCount > 0) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(localizedUiText("Resolve linked child accounts first"),
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(localizedUiText("The parent account cannot be submitted for deletion until every linked child is safely independent or the child account has been resolved by an authorized process."),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (childArray != null) {
                                        for (index in 0 until childArray.length()) {
                                            val child = childArray.optJSONObject(index) ?: continue
                                            val childId = child.optString("publicUserId")
                                            val childName = child.optString("displayName").ifBlank { childId }
                                            val adultVerified = child.optBoolean("adultVerified")
                                            val ageStatus = child.optString("adultVerificationStatus", "NOT_REVIEWED")
                                            val childRequestStatus = child.optString("deletionRequestStatus")
                                            HorizontalDivider()
                                            Text(childName, fontWeight = FontWeight.SemiBold)
                                            Text(localizedUiText("Account ID: ") + childId,
                                                style = MaterialTheme.typography.bodySmall)
                                            when {
                                                adultVerified -> Text(localizedUiText("Over-18 eligibility is verified. The adult learner must sign in to their own learner session and complete independent-account conversion; a guardian cannot delete their account."),
                                                    style = MaterialTheme.typography.bodySmall)
                                                ageStatus != "REJECTED" -> Text(localizedUiText("A reviewer must confirm that this child is under 18 before a guardian-requested deletion can be submitted."),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error)
                                                childRequestStatus.isNotBlank() -> Text(localizedUiText("Child deletion request status: ") + childRequestStatus +
                                                    ". " + localizedUiText("The parent account remains blocked until the child account is resolved."),
                                                    style = MaterialTheme.typography.bodySmall)
                                                else -> Button(
                                                    onClick = {
                                                        onRequestChildDeletion(childId, "DELETE", deletionReasonCategory,
                                                            deletionReasonDetails, deletionManualIdentityReview)
                                                    },
                                                    enabled = !busy && deletionConfirmation == "DELETE" &&
                                                        deletionReasonCategory.isNotBlank() &&
                                                        (deletionManualIdentityReview || deletionVerificationId.isNotBlank()) &&
                                                        (deletionReasonCategory != "OTHER" || deletionReasonDetails.trim().length >= 5),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(localizedUiText("Request deletion of this child account"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = deletionConfirmation,
                            onValueChange = { deletionConfirmation = it.trim().uppercase().take(20) },
                            label = { Text(localizedUiText("Type DELETE to confirm")) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (childCount == 0) {
                            Button(
                                onClick = {
                                    val confirmedText = deletionConfirmation
                                    val reason = deletionReasonCategory
                                    val details = deletionReasonDetails
                                    val manual = deletionManualIdentityReview
                                    deletionConfirmation = ""
                                    onRequestDeletion(confirmedText, reason, details, manual)
                                },
                                enabled = !busy && deletionConfirmation == "DELETE" &&
                                    deletionReasonCategory.isNotBlank() &&
                                    (deletionManualIdentityReview || deletionVerificationId.isNotBlank()) &&
                                    (deletionReasonCategory != "OTHER" || deletionReasonDetails.trim().length >= 5),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(localizedUiText("Submit deletion request"))
                            }
                        }
                    }

                    if (deletionRequest.optBoolean("loadFailed")) {
                        Text(localizedUiText("Request status could not be loaded. Refresh before making a new request."),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onRefreshDeletion, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(localizedUiText("Refresh deletion status"))
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
