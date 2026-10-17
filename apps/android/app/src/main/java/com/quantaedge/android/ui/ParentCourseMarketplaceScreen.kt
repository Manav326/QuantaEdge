package com.quantaedge.android.ui

import org.json.JSONObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

private fun JSONObject.qeText(vararg keys: String): String {
    for (key in keys) {
        val value = optString(key, "").trim()
        if (value.isNotBlank() && value != "null") return value
    }
    return ""
}

private fun JSONObject.qeLong(vararg keys: String): Long {
    for (key in keys) {
        val value = optLong(key, 0L)
        if (value > 0L) return value
    }
    return 0L
}

private fun courseMoney(paise: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)

@Composable
fun ParentCourseMarketplaceScreen(
    offers: List<JSONObject>,
    requests: List<JSONObject>,
    entitlements: List<JSONObject>,
    children: List<JSONObject>,
    language: String,
    loading: Boolean,
    loadFailed: Boolean,
    busy: Boolean,
    busyCourseId: Long,
    error: String,
    status: String,
    onRefresh: () -> Unit,
    onBuy: (Long, String, Long) -> Unit,
    onAddFree: (Long, String, Long) -> Unit,
    onDismissRequest: (Long) -> Unit,
    onTransfer: (Long, Long) -> Unit,
    onMyLearning: () -> Unit,
    onBack: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var pendingTransfer by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(t("FAMILY COURSE MARKETPLACE", "परिवार का कोर्स मार्केटप्लेस"),
                    style = MaterialTheme.typography.labelMedium, color = LocalQuantaEdgeSemanticColors.current.primaryText,
                    fontWeight = FontWeight.Bold)
                Text(t("Courses for you or your child", "आपके या आपके बच्चे के लिए कोर्स"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onBack, enabled = !busy) { Text(t("Back", "वापस")) }
        }
        Text(t(
            "Choose the recipient before checkout. A course you own can be transferred once to a linked child; that transfer is permanent.",
            "भुगतान से पहले प्राप्तकर्ता चुनें। आपके स्वामित्व वाला कोर्स एक बार जुड़े बच्चे को दिया जा सकता है; ट्रांसफर स्थायी है।"
        ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onMyLearning, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("My learning", "मेरी पढ़ाई"))
            }
            OutlinedButton(onClick = onRefresh, enabled = !busy && !loading, modifier = Modifier.weight(1f)) {
                Text(t("Refresh", "रिफ्रेश"))
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (loadFailed) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Family courses could not be loaded", "परिवार के कोर्स लोड नहीं हो सके"), fontWeight = FontWeight.SemiBold)
                    Text(t("Retry to fetch the latest published offers, requests and entitlements.", "नए प्रकाशित कोर्स, अनुरोध और entitlements लोड करने के लिए फिर प्रयास करें।"),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRefresh, enabled = !busy) { Text(t("Retry", "फिर प्रयास करें")) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Purchase requests", "कोर्स खरीदने के अनुरोध") + " (" + requests.size + ")",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (requests.isEmpty()) {
                    Text(t("No pending requests from your children.", "आपके बच्चों की ओर से कोई लंबित अनुरोध नहीं है।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                requests.forEach { request ->
                    val requestId = request.qeLong("request_id", "id")
                    val studentId = request.qeLong("student_id", "studentId")
                    val courseId = request.qeLong("course_id", "courseId")
                    val price = request.optLong("price_paise", 0L)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(request.qeText("course_name", "title").ifBlank { t("Course request", "कोर्स अनुरोध") },
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(t("Requested by: ", "अनुरोधकर्ता: ") + request.qeText("student_name", "display_name"),
                                style = MaterialTheme.typography.bodySmall)
                            Text(if (price == 0L) t("Free", "निःशुल्क") else courseMoney(price),
                                color = if (price == 0L) LocalQuantaEdgeSemanticColors.current.success else LocalQuantaEdgeSemanticColors.current.primaryText,
                                style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = {
                                    if (price == 0L) onAddFree(courseId, "CHILD", studentId)
                                    else onBuy(courseId, "CHILD", studentId)
                                }, enabled = !busy && courseId > 0L && studentId > 0L, modifier = Modifier.weight(1f)) {
                                    Text(t("Buy for this child", "इस बच्चे के लिए"))
                                }
                                TextButton(onClick = { onDismissRequest(requestId) }, enabled = !busy && requestId > 0L) {
                                    Text(t("Dismiss", "खारिज"))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Published courses", "उपलब्ध कोर्स"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (offers.isEmpty()) {
                    Text(t("There are no published course offers yet.", "अभी कोई प्रकाशित कोर्स ऑफर नहीं है।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                offers.forEach { offer ->
                    val courseId = offer.qeLong("course_id", "courseId", "id")
                    val title = offer.qeText("title", "course_name", "display_name").ifBlank { t("Course", "कोर्स") }
                    val price = offer.optLong("price_paise", 0L)
                    val ownedByParent = offer.optBoolean("owned_by_parent")
                    val ownedByChild = offer.optBoolean("owned_by_child")
                    val ownedChildIds = offer.qeText("owned_child_ids").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(offer.qeText("course_code").ifBlank { offer.qeText("curriculum_version") },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (ownedByParent || ownedByChild) t("Owned", "उपलब्ध")
                                    else if (price == 0L) t("Free", "निःशुल्क") else courseMoney(price),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (ownedByParent || ownedByChild || price == 0L) LocalQuantaEdgeSemanticColors.current.success else LocalQuantaEdgeSemanticColors.current.primaryText,
                                    fontWeight = FontWeight.Bold)
                            }
                            offer.qeText("description").takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (ownedByParent) {
                                Text(t("You already own this course. Transfer it from the section below instead of paying again.",
                                    "यह कोर्स पहले से आपके पास है। दोबारा भुगतान करने के बजाय नीचे से स्थायी ट्रांसफर करें।"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Button(onClick = { if (price == 0L) onAddFree(courseId, "SELF", 0L) else onBuy(courseId, "SELF", 0L) },
                                    enabled = !busy && courseId > 0L, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (price == 0L) t("Add free course for me", "अपने लिए निःशुल्क कोर्स") else t("Buy for me", "अपने लिए खरीदें"))
                                }
                                if (children.isNotEmpty()) {
                                    Text(t("For a linked child", "जुड़े बच्चे के लिए"), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    children.forEach { child ->
                                        val childId = child.qeLong("id", "student_id")
                                        val alreadyOwned = childId in ownedChildIds
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Column(Modifier.weight(1f)) {
                                                Text(child.qeText("display_name", "student_name").ifBlank { t("Young Learner", "बाल शिक्षार्थी") },
                                                    style = MaterialTheme.typography.bodyMedium)
                                                if (alreadyOwned) Text(t("Already has access", "पहले से पहुँच है"),
                                                    style = MaterialTheme.typography.labelSmall, color = LocalQuantaEdgeSemanticColors.current.success)
                                            }
                                            TextButton(
                                                onClick = { if (price == 0L) onAddFree(courseId, "CHILD", childId) else onBuy(courseId, "CHILD", childId) },
                                                enabled = !busy && courseId > 0L && childId > 0L && !alreadyOwned && price >= 0L
                                            ) { Text(t(if (price == 0L) "Add" else "Buy", if (price == 0L) "जोड़ें" else "खरीदें")) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Your transferable courses", "ट्रांसफर किए जा सकने वाले कोर्स") + " (" + entitlements.size + ")",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t("A transfer is permanent. You cannot take the course back or revoke your child's access.",
                    "ट्रांसफर स्थायी है। आप कोर्स वापस नहीं ले सकते और बच्चे की पहुँच रद्द नहीं कर सकते।"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entitlements.isEmpty()) {
                    Text(t("No personally owned purchased course is ready to transfer.", "अभी कोई व्यक्तिगत खरीदा हुआ कोर्स ट्रांसफर के लिए उपलब्ध नहीं है।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entitlements.forEach { entitlement ->
                    val entitlementId = entitlement.qeLong("entitlement_id", "id")
                    val currentStudentId = entitlement.qeLong("holder_student_id")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entitlement.qeText("course_name", "title").ifBlank { t("Course", "कोर्स") },
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            children.filter { it.qeLong("id") != currentStudentId }.forEach { child ->
                                val childId = child.qeLong("id")
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(child.qeText("display_name"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { pendingTransfer = entitlementId to childId },
                                        enabled = !busy && entitlementId > 0L && childId > 0L) {
                                        Text(t("Transfer permanently", "स्थायी ट्रांसफर"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingTransfer?.let { transfer ->
        val childName = children.firstOrNull { it.qeLong("id") == transfer.second }?.qeText("display_name").orEmpty()
        AlertDialog(
            onDismissRequest = { if (!busy) pendingTransfer = null },
            title = { Text(t("Permanent course transfer", "कोर्स का स्थायी ट्रांसफर")) },
            text = { Text(t(
                "Transfer this course to " + childName + "? The parent entitlement will be permanently removed and this action cannot be reversed.",
                "क्या यह कोर्स " + childName + " को दें? अभिभावक का entitlement स्थायी रूप से हट जाएगा और यह कार्रवाई वापस नहीं की जा सकती।"
            )) },
            confirmButton = {
                Button(onClick = {
                    onTransfer(transfer.first, transfer.second)
                    pendingTransfer = null
                }, enabled = !busy) { Text(t("Transfer permanently", "स्थायी ट्रांसफर")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTransfer = null }, enabled = !busy) { Text(t("Cancel", "रद्द करें")) }
            }
        )
    }
}
