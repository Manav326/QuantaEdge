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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun AccountLifecycleScreen(
    data: JSONObject,
    language: String,
    busy: Boolean,
    error: String,
    status: String,
    onRefresh: () -> Unit,
    onRequestAdultReview: () -> Unit,
    onRequestConversionOtp: (String, (String?) -> Unit) -> Unit,
    onCompleteConversion: (String, String, String) -> Unit,
    onRequestParentReview: () -> Unit,
    onTransitionToParent: () -> Unit
) {
    val hi = language == "hi"
    fun t(en: String, hindi: String) = if (hi) hindi else en
    fun displayStatus(value: String): String = when (value.uppercase()) {
        "NOT_REQUESTED" -> t("Not requested", "अनुरोध नहीं किया गया")
        "PENDING" -> t("Pending", "लंबित")
        "APPROVED" -> t("Approved", "स्वीकृत")
        "REJECTED" -> t("Rejected", "अस्वीकृत")
        "EXPIRED" -> t("Expired", "समाप्त")
        "VERIFIED" -> t("Verified", "सत्यापित")
        "NOT_VERIFIED" -> t("Not verified", "सत्यापित नहीं")
        else -> value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
    val adultCurrent = data.optBoolean("adult_verification_current")
    val linked = data.optBoolean("independentAccountLinked")
    val role = data.optString("role")
    val adultStatus = data.optString("adult_verification_status", "NOT_REQUESTED")
    val parentStatus = data.optString("parent_eligibility_status", "NOT_REQUESTED")
    val parentCurrent = data.optBoolean("parent_eligibility_current")
    val canTransition = data.optBoolean("canTransitionToParent")
    var consent by rememberSaveable { mutableStateOf(false) }
    var mobile by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var devCode by rememberSaveable { mutableStateOf("") }
    var otpRequested by rememberSaveable { mutableStateOf(false) }
    var confirmTransition by rememberSaveable { mutableStateOf(false) }

    if (confirmTransition) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmTransition = false },
            title = { Text(t("Switch to parent account?", "Parent account में बदलें?")) },
            text = { Text(t("Your learner history stays linked. You will need to sign in again.",
                "आपकी learning history linked रहेगी। आपको फिर से sign in करना होगा।")) },
            confirmButton = {
                Button(onClick = { confirmTransition = false; onTransitionToParent() }, enabled = !busy) {
                    Text(t("Confirm", "पुष्टि करें"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTransition = false }, enabled = !busy) {
                    Text(t("Cancel", "रद्द करें"))
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Account & eligibility", "Account और eligibility"),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !busy) { Text(t("Refresh", "Refresh")) }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
            Text(t("I confirm and consent to this request.", "मैं इस अनुरोध की पुष्टि और सहमति देता/देती हूँ।"),
                style = MaterialTheme.typography.bodySmall)
        }

        Text(t("Adult verification", "वयस्कता सत्यापन"), style = MaterialTheme.typography.titleMedium)
        Text(t("Status: ", "स्थिति: ") + displayStatus(adultStatus) + if (adultCurrent) " · " + t("Verified", "सत्यापित") else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        if (!adultCurrent) {
            Button(onClick = onRequestAdultReview,
                enabled = !busy && consent && adultStatus != "PENDING",
                modifier = Modifier.fillMaxWidth()) {
                Text(if (adultStatus == "PENDING") t("Request pending", "अनुरोध लंबित") else t("Request verification", "सत्यापन अनुरोध करें"))
            }
        }

        if (!linked) {
            Text(t("Independent learner account", "स्वतंत्र विद्यार्थी खाता"), style = MaterialTheme.typography.titleMedium)
            Text(t("Verify your mobile and set a password after adult verification.",
                "वयस्कता सत्यापन के बाद mobile verify करके password बनाएँ।"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedTextField(
                value = mobile,
                onValueChange = { mobile = it.filter(Char::isDigit).take(10); otpRequested = false; otp = ""; devCode = "" },
                label = { Text(t("Mobile (10 digits)", "Mobile (10 अंक)")) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
            )
            if (adultCurrent && !otpRequested) {
                Button(onClick = { onRequestConversionOtp(mobile) { code -> devCode = code.orEmpty(); otpRequested = true } },
                    enabled = !busy && consent && mobile.length == 10, modifier = Modifier.fillMaxWidth()) {
                    Text(t("Send OTP", "OTP भेजें"))
                }
            }
            if (otpRequested) {
                if (devCode.isNotBlank()) Text(t("Development OTP: ", "Development OTP: ") + devCode,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                    label = { Text(t("OTP (6 digits)", "OTP (6 अंक)")) }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text(t("New password (8+ characters)", "नया password (8+ अक्षर)")) },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { onCompleteConversion(mobile, otp, password) },
                    enabled = !busy && consent && otp.length == 6 && password.length >= 8,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(t("Create independent account", "Independent account बनाएँ"))
                }
            }
        }

        if (linked && role == "LEARNER") {
            Text(t("Parent eligibility", "अभिभावक पात्रता"), style = MaterialTheme.typography.titleMedium)
            Text(t("Status: ", "स्थिति: ") + displayStatus(parentStatus) + if (parentCurrent) " · " + t("Approved", "स्वीकृत") else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (!parentCurrent && adultCurrent) {
                Button(onClick = onRequestParentReview,
                    enabled = !busy && consent && parentStatus != "PENDING",
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (parentStatus == "PENDING") t("Request pending", "अनुरोध लंबित")
                        else t("Request parent eligibility", "Parent eligibility अनुरोध करें"))
                }
            }
            if (canTransition) {
                Button(onClick = { confirmTransition = true }, enabled = !busy && consent,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(t("Switch to parent account", "Parent account में बदलें"))
                }
            }
        }


    }
}
