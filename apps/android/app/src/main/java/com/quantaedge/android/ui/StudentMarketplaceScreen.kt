package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

@Composable
fun StudentMarketplaceScreen(
    offers: List<JSONObject>,
    loading: Boolean,
    loadFailed: Boolean,
    language: String,
    busyCourseId: Long?,
    busy: Boolean,
    billing: BillingAddressBookBindings,
    onBuy: (Long, String, Long) -> Unit,
    onJoinFree: (Long) -> Unit,
    onAskParent: (Long) -> Unit,
    onRefresh: () -> Unit,
    onAllCourses: () -> Unit,
    pendingVerification: Boolean = false,
    onRetryVerification: () -> Unit = {}
) {
    val hi = language == "hi"
    var discountCode by remember { mutableStateOf("") }
    var checkoutCourseId by remember { mutableStateOf(0L) }
    var selectedBillingAddressId by remember { mutableStateOf<Long?>(null) }
    var billingCheckoutMessage by remember { mutableStateOf("") }
    val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    fun requestPaidPurchase(courseId: Long) {
        billingCheckoutMessage = ""
        if (billing.loading) {
            billingCheckoutMessage = if (hi) "Saved billing address load हो रहा है। थोड़ी देर बाद फिर कोशिश करें।"
                else "Saved billing addresses are still loading. Please retry in a moment."
            return
        }
        if (billing.addresses.isEmpty()) {
            billingCheckoutMessage = if (hi) "Checkout से पहले ऊपर billing address save करें।"
                else "Save a billing address above before checkout."
            return
        }
        val preferred = billing.addresses.firstOrNull { it.optBoolean("is_default") } ?: billing.addresses.first()
        if (billing.addresses.size == 1) {
            if (!billingAddressIsComplete(preferred)) {
                billingCheckoutMessage = if (hi) "Checkout से पहले billing address पूरा करें।"
                    else "Complete your billing address before checkout."
                return
            }
            onBuy(courseId, discountCode.trim(), billingAddressId(preferred))
            return
        }
        checkoutCourseId = courseId
        selectedBillingAddressId = preferred.let { billingAddressId(it) }
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (hi) "कोर्स मार्केटप्लेस" else "Course Marketplace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (hi) "अपनी अगली सीखने की यात्रा चुनें। भुगतान Razorpay के सुरक्षित checkout से होगा।"
                else "Choose your next learning path. Course payments are handled through secure Razorpay Checkout.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAllCourses, enabled = !busy) { Text(if (hi) "आपके courses" else "Your courses") }
            OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("↻") }
        }
        OutlinedTextField(
            value = discountCode,
            onValueChange = { discountCode = it.uppercase(Locale.ROOT) },
            label = { Text(if (hi) "Offer code (वैकल्पिक)" else "Offer code (optional)") },
            placeholder = { Text(if (hi) "संस्था द्वारा दिया गया code" else "Code supplied by your institution") },
            singleLine = true,
            enabled = !busy && !pendingVerification,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (hi) "Code केवल उसी course पर लागू होगा जिसके लिए HRMS ने approval दिया है। कीमत और tax server पर तय होते हैं।"
            else "Codes only work for the course approved by HRMS. The server calculates your discount and tax.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (offers.any { it.optLong("price_paise", -1L) > 0L &&
                it.optString("currency", "INR") == "INR" && !it.optBoolean("parent_managed") &&
                !it.optBoolean("has_access") && !it.optBoolean("purchased") }) {
            BillingAddressBook(
                addresses = billing.addresses,
                loading = billing.loading,
                busy = busy,
                language = language,
                compactOnly = true,
                errorMessage = billing.error,
                statusMessage = billing.status,
                onSave = billing.onSave,
                onMakeDefault = billing.onMakeDefault,
                onDelete = billing.onDelete,
                onOpenProfile = billing.onOpenProfile
            )
        }
        if (billingCheckoutMessage.isNotBlank()) {
            Text(billingCheckoutMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (pendingVerification) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hi) "भुगतान की पुष्टि बाकी है" else "Payment verification is pending",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (hi) "इस order को सत्यापित करें। अभी दोबारा भुगतान न करें।"
                        else "Reconcile the saved order before trying to pay again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRetryVerification, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hi) "भुगतान फिर से सत्यापित करें" else "Retry payment verification")
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (loadFailed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hi) "Marketplace अभी लोड नहीं हुआ" else "Marketplace couldn't be loaded", style = MaterialTheme.typography.titleMedium)
                    Text(if (hi) "कनेक्शन जाँचें और फिर प्रयास करें।" else "Check your connection and retry.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh, enabled = !busy) { Text(if (hi) "फिर प्रयास करें" else "Retry") }
                }
            }
        } else if (!loading && offers.isEmpty()) {
            StudentEnrollmentEmptyState(
                icon = "▦",
                title = localizedUiText("No courses are available right now"),
                message = localizedUiText("The Marketplace has no published course offers at the moment. Refresh to check again, or open My Courses to review your current enrolments."),
                primaryLabel = localizedUiText("Refresh Marketplace"),
                onPrimary = onRefresh,
                secondaryLabel = localizedUiText("My Courses"),
                onSecondary = onAllCourses
            )
        }
        offers.forEach { offer ->
            val id = offer.optLong("course_id", offer.optLong("courseId"))
            val title = offer.optString("title").ifBlank { offer.optString("course_name", "Course") }
            val courseTypeCode = offer.optString("course_type_code").trim().uppercase(Locale.ROOT)
            val courseTypeLabel = marketplaceCourseTypeLabel(courseTypeCode, hi)
            val publishedPackCount = offer.optInt("test_pack_count", 0).coerceAtLeast(0)
            val publishedTestCount = offer.optInt("assessment_count", 0).coerceAtLeast(0)
            val publishedQuestionCount = offer.optInt("assessment_question_count", 0).coerceAtLeast(0)
            val pricePaise = offer.optLong("price_paise", -1L)
            val currency = offer.optString("currency", "INR")
            val owned = offer.optBoolean("has_access") || offer.optBoolean("purchased")
            val subjects = offer.optJSONArray("subjects")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            }.orEmpty()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(offer.optString("course_code").ifBlank { offer.optString("curriculum_version") },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (courseTypeLabel.isNotBlank()) {
                                Text(courseTypeLabel, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            when {
                                owned -> if (hi) "आपका course" else "Owned"
                                pricePaise > 0L && currency == "INR" -> inr.format(pricePaise / 100.0)
                                pricePaise == 0L -> if (hi) "मुफ़्त" else "Free"
                                else -> if (hi) "अभी उपलब्ध नहीं" else "Unavailable"
                            },
                            style = MaterialTheme.typography.titleSmall, color = when {
                                owned || pricePaise == 0L -> LocalQuantaEdgeSemanticColors.current.success
                                pricePaise > 0L && currency == "INR" -> LocalQuantaEdgeSemanticColors.current.primaryText
                                else -> MaterialTheme.colorScheme.error
                            }, fontWeight = FontWeight.Bold
                        )
                    }
                    offer.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (courseTypeCode == "ASSESSMENT") {
                        Text(marketplaceAssessmentInventorySummary(
                            hi, publishedPackCount, publishedTestCount, publishedQuestionCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (subjects.isNotEmpty()) {
                        Text((if (hi) "विषय: " else "Subjects: ") +
                            subjects.joinToString(" · ") { it.optString("subject_name", it.optString("subject_code")) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        owned -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(if (hi) "यह course आपके Courses में है।" else "This course is already in your Courses.",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        pricePaise > 0L && currency == "INR" && offer.optBoolean("parent_managed") -> Button(
                            onClick = { onAskParent(id) }, enabled = !busy && id > 0L && busyCourseId != id, modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busyCourseId == id) { if (hi) "अनुरोध भेज रहा है…" else "Sending request…" }
                                else if (hi) "अभिभावक से खरीदने को कहें" else "Ask parent to buy")
                        }
                        pricePaise > 0L && currency == "INR" -> Button(
                            onClick = { requestPaidPurchase(id) }, enabled = !busy && !pendingVerification && id > 0L && busyCourseId != id, modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busyCourseId == id) { if (hi) "Checkout खुल रहा है…" else "Opening checkout…" }
                                else if (hi) "कोर्स खरीदें" else "Buy course")
                        }
                        pricePaise == 0L -> OutlinedButton(
                            onClick = { onJoinFree(id) }, enabled = !busy && id > 0L, modifier = Modifier.fillMaxWidth()
                        ) { Text(if (hi) "मुफ़्त course जोड़ें" else "Join free course") }
                        else -> Text(if (hi) "यह offer अभी खरीदने के लिए उपलब्ध नहीं है।" else "This offer is not currently available for purchase.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (checkoutCourseId > 0L) {
        BillingAddressPickerDialog(
            addresses = billing.addresses,
            selectedAddressId = selectedBillingAddressId,
            language = language,
            onSelect = { selectedBillingAddressId = it },
            onDismiss = { checkoutCourseId = 0L },
            onConfirm = {
                val courseId = checkoutCourseId
                val addressId = selectedBillingAddressId
                checkoutCourseId = 0L
                if (addressId != null) onBuy(courseId, discountCode.trim(), addressId)
            }
        )
    }
}
