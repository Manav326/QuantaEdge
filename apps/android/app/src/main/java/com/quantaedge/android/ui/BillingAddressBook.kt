package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal fun billingAddressId(address: JSONObject): Long =
    address.optLong("id", address.optLong("addressId", 0L))

internal fun billingAddressIsComplete(address: JSONObject): Boolean {
    val body = address.optJSONObject("billingAddress") ?: JSONObject()
    return listOf("line1", "city", "state", "postalCode").all { body.optString(it).isNotBlank() }
}

private fun addressValue(address: JSONObject, key: String): String = address.optString(key).takeUnless { it == "null" }.orEmpty()
private fun billingValue(address: JSONObject, key: String): String =
    (address.optJSONObject("billingAddress") ?: JSONObject()).optString(key).takeUnless { it == "null" }.orEmpty()

private fun linesForAddress(address: JSONObject): List<String> {
    val cityState = listOf(billingValue(address, "city"), billingValue(address, "state"))
        .filter { it.isNotBlank() }.joinToString(", ")
    val pinCountry = listOf(billingValue(address, "postalCode"), billingValue(address, "country"))
        .filter { it.isNotBlank() }.joinToString(" – ")
    return listOf(
        billingValue(address, "line1"), billingValue(address, "line2"),
        cityState, pinCountry
    ).filter { it.isNotBlank() }
}

@Composable
fun BillingAddressBook(
    addresses: List<JSONObject>,
    loading: Boolean,
    busy: Boolean,
    language: String,
    compactOnly: Boolean,
    errorMessage: String = "",
    statusMessage: String = "",
    onSave: (Long?, JSONObject, (Boolean) -> Unit) -> Unit,
    onMakeDefault: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenProfile: () -> Unit = {}
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var formOpen by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("Home") }
    var billingType by rememberSaveable { mutableStateOf("INDIVIDUAL") }
    var legalName by rememberSaveable { mutableStateOf("") }
    var gstin by rememberSaveable { mutableStateOf("") }
    var gstStateCode by rememberSaveable { mutableStateOf("") }
    var line1 by rememberSaveable { mutableStateOf("") }
    var line2 by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("") }
    var postalCode by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("India") }
    var contactEmail by rememberSaveable { mutableStateOf("") }
    var contactPhone by rememberSaveable { mutableStateOf("") }
    var localValidationError by rememberSaveable { mutableStateOf("") }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = addresses.firstOrNull { it.optBoolean("is_default") } ?: addresses.firstOrNull()

    fun resetDraft() {
        editingId = null
        label = "Home"
        billingType = "INDIVIDUAL"
        legalName = ""
        gstin = ""
        gstStateCode = ""
        line1 = ""
        line2 = ""
        city = ""
        state = ""
        postalCode = ""
        country = "India"
        contactEmail = ""
        contactPhone = ""
        localValidationError = ""
        formOpen = true
    }
    fun editAddress(address: JSONObject) {
        val details = address.optJSONObject("billingAddress") ?: JSONObject()
        editingId = billingAddressId(address).takeIf { it > 0L }
        label = addressValue(address, "label").ifBlank { t("Billing address", "Billing address") }
        billingType = addressValue(address, "billing_type").ifBlank { "INDIVIDUAL" }
        legalName = addressValue(address, "legal_name")
        gstin = addressValue(address, "gstin")
        gstStateCode = addressValue(address, "gst_state_code")
        line1 = details.optString("line1").takeUnless { it == "null" }.orEmpty()
        line2 = details.optString("line2").takeUnless { it == "null" }.orEmpty()
        city = details.optString("city").takeUnless { it == "null" }.orEmpty()
        state = details.optString("state").takeUnless { it == "null" }.orEmpty()
        postalCode = details.optString("postalCode").takeUnless { it == "null" }.orEmpty()
        country = details.optString("country").takeUnless { it == "null" }.orEmpty().ifBlank { "India" }
        contactEmail = addressValue(address, "contact_email")
        contactPhone = addressValue(address, "contact_phone")
        localValidationError = ""
        formOpen = true
    }

    LaunchedEffect(addresses, compactOnly, loading) {
        if (compactOnly && !loading) {
            val preferred = addresses.firstOrNull { it.optBoolean("is_default") } ?: addresses.firstOrNull()
            when {
                preferred == null && !formOpen -> resetDraft()
                preferred != null && !billingAddressIsComplete(preferred) &&
                    editingId != billingAddressId(preferred) -> editAddress(preferred)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t("Billing address", "Billing address"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (compactOnly) t("Your saved billing details", "आपकी saved billing details")
                    else t("Manage the addresses used for purchases and billing documents.", "Purchases और billing documents के लिए addresses यहाँ manage करें।"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!compactOnly) {
                OutlinedButton(onClick = { resetDraft() }, enabled = !busy && !loading) {
                    Text(t("＋ Add", "＋ जोड़ें"))
                }
            }
        }
        if (errorMessage.isNotBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (statusMessage.isNotBlank()) Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            Text(t("Loading saved addresses…", "Saved addresses load हो रहे हैं…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (compactOnly) {
            if (selected != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(addressValue(selected, "label").ifBlank { t("Billing address", "Billing address") },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(addressValue(selected, "legal_name"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(onClick = { editAddress(selected) }, enabled = !busy) { Text("✎ " + t("Edit", "Edit")) }
                        }
                        linesForAddress(selected).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        addressValue(selected, "gstin").takeIf { it.isNotBlank() }?.let {
                            Text("GSTIN: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        addressValue(selected, "contact_email").takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        addressValue(selected, "contact_phone").takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        if (addresses.size > 1) Text(
                            t("You have " + addresses.size + " saved addresses. Checkout will ask you to choose one.",
                                "आपके " + addresses.size + " saved addresses हैं। Checkout पर एक चुनना होगा।"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenProfile, enabled = !busy) { Text(t("Manage in My Profile", "My Profile में manage करें")) }
                    }
                }
            } else if (!formOpen) {
                Text(t("Add your billing address before checkout.", "Checkout से पहले billing address जोड़ें।"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (addresses.isEmpty()) {
            Text(t("No billing address saved yet.", "अभी कोई billing address save नहीं है।"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            addresses.forEach { address ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(addressValue(address, "label").ifBlank { t("Billing address", "Billing address") },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(addressValue(address, "legal_name"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (address.optBoolean("is_default")) Text(t("Default", "Default"),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { editAddress(address) }, enabled = !busy) { Text("✎ " + t("Edit", "Edit")) }
                        }
                        linesForAddress(address).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        addressValue(address, "gstin").takeIf { it.isNotBlank() }?.let { Text("GSTIN: $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!address.optBoolean("is_default")) TextButton(onClick = { onMakeDefault(billingAddressId(address)) }, enabled = !busy) {
                                Text(t("Make default", "Default बनाएं"))
                            }
                            TextButton(onClick = { pendingDeleteId = billingAddressId(address) }, enabled = !busy) {
                                Text(t("Delete", "Delete करें"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        if (formOpen) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (editingId == null) t("Add billing address", "Billing address जोड़ें")
                            else t("Edit billing address", "Billing address edit करें"),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (!(compactOnly && (selected == null || !billingAddressIsComplete(selected)))) {
                            TextButton(onClick = { formOpen = false; localValidationError = "" }, enabled = !busy) { Text(t("Cancel", "Cancel")) }
                        }
                    }
                    OutlinedTextField(value = label, onValueChange = { label = it.take(80) },
                        label = { Text(t("Address label", "Address का नाम")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text(t("Billing type", "Billing type"), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = billingType == "INDIVIDUAL", onClick = { billingType = "INDIVIDUAL" },
                            label = { Text(t("Individual", "Individual")) }, enabled = !busy)
                        FilterChip(selected = billingType == "BUSINESS", onClick = { billingType = "BUSINESS" },
                            label = { Text(t("Business", "Business")) }, enabled = !busy)
                    }
                    OutlinedTextField(value = legalName, onValueChange = { legalName = it.take(240) },
                        label = { Text(t("Billing name", "Billing name")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gstin, onValueChange = { gstin = it.uppercase().take(15) },
                        label = { Text(t("GSTIN (optional)", "GSTIN (optional)")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gstStateCode, onValueChange = { gstStateCode = it.filter(Char::isDigit).take(2) },
                        label = { Text(t("GST state code", "GST state code")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = line1, onValueChange = { line1 = it.take(180) },
                        label = { Text(t("Address line 1", "Address line 1")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = line2, onValueChange = { line2 = it.take(180) },
                        label = { Text(t("Address line 2 (optional)", "Address line 2 (optional)")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = city, onValueChange = { city = it.take(80) },
                        label = { Text(t("City", "City")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state, onValueChange = { state = it.take(80) },
                        label = { Text(t("State", "State")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = postalCode, onValueChange = { postalCode = it.take(16) },
                        label = { Text(t("PIN code", "PIN code")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = country, onValueChange = { country = it.take(80) },
                        label = { Text(t("Country", "Country")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contactEmail, onValueChange = { contactEmail = it.take(320) },
                        label = { Text(t("Billing email (optional)", "Billing email (optional)")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contactPhone, onValueChange = { contactPhone = it.take(40) },
                        label = { Text(t("Billing phone (optional)", "Billing phone (optional)")) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (localValidationError.isNotBlank()) Text(localValidationError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!(compactOnly && (selected == null || !billingAddressIsComplete(selected)))) {
                            OutlinedButton(onClick = { formOpen = false; localValidationError = "" },
                                enabled = !busy, modifier = Modifier.weight(1f)) { Text(t("Cancel", "Cancel")) }
                        }
                        Button(onClick = {
                            if (legalName.isBlank() || line1.isBlank() || city.isBlank() || state.isBlank() || postalCode.isBlank()) {
                                localValidationError = t("Enter billing name, address line 1, city, state and PIN code.",
                                    "Billing name, address line 1, city, state और PIN code भरें।")
                                return@Button
                            }
                            val address = JSONObject()
                                .put("line1", line1.trim()).put("line2", line2.trim())
                                .put("city", city.trim()).put("state", state.trim())
                                .put("postalCode", postalCode.trim()).put("country", country.trim().ifBlank { "India" })
                            val body = JSONObject()
                                .put("label", label.trim().ifBlank { "Billing address" })
                                .put("billingType", billingType)
                                .put("legalName", legalName.trim())
                                .put("billingAddress", address)
                                .put("isDefault", editingId?.let { id -> addresses.firstOrNull { billingAddressId(it) == id }?.optBoolean("is_default") } ?: (addresses.isEmpty()))
                            if (gstin.isNotBlank()) body.put("gstin", gstin.trim())
                            if (gstStateCode.isNotBlank()) body.put("gstStateCode", gstStateCode.trim())
                            if (contactEmail.isNotBlank()) body.put("contactEmail", contactEmail.trim())
                            if (contactPhone.isNotBlank()) body.put("contactPhone", contactPhone.trim())
                            onSave(editingId, body) { saved ->
                                if (saved) {
                                    formOpen = false
                                    editingId = null
                                    localValidationError = ""
                                }
                            }
                        }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (busy) t("Saving…", "Save हो रहा है…") else t("Save address", "Address save करें"))
                        }
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        val pending = addresses.firstOrNull { billingAddressId(it) == id }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDeleteId = null },
            title = { Text(t("Delete billing address?", "Billing address delete करें?")) },
            text = { Text(t("Previous orders keep their saved details.", "पुराने orders में saved details बनी रहेंगी।") + " " + addressValue(pending ?: JSONObject(), "label")) },
            confirmButton = {
                Button(onClick = { pendingDeleteId = null; onDelete(id) }, enabled = !busy) { Text(t("Delete", "Delete करें")) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }, enabled = !busy) { Text(t("Cancel", "Cancel")) } }
        )
    }
}

@Composable
fun BillingAddressPickerDialog(
    addresses: List<JSONObject>,
    selectedAddressId: Long?,
    language: String,
    onSelect: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Choose a billing address", "Billing address चुनें")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(t("Select the address for this purchase.", "इस purchase के लिए address चुनें।"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                addresses.forEach { address ->
                    val id = billingAddressId(address)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selectedAddressId == id, onClick = { onSelect(id) },
                            enabled = billingAddressIsComplete(address) && id > 0L)
                        Column(modifier = Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(addressValue(address, "legal_name"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(addressValue(address, "label"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            linesForAddress(address).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (!billingAddressIsComplete(address)) Text(t("Edit and complete this address in My Profile.", "इस address को My Profile में edit करके पूरा करें।"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = addresses.any { billingAddressId(it) == selectedAddressId && billingAddressIsComplete(it) }) {
                Text(t("Continue", "जारी रखें"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "Cancel")) } }
    )
}
