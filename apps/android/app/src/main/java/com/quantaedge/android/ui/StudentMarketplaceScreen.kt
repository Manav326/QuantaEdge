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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onBuy: (Long) -> Unit,
    onJoinFree: (Long) -> Unit,
    onRefresh: () -> Unit,
    onAllCourses: () -> Unit
) {
    val hi = language == "hi"
    val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (hi) "अभी कोई course खरीदने के लिए प्रकाशित नहीं है" else "No courses are currently listed for purchase", style = MaterialTheme.typography.titleMedium)
                    Text(if (hi) "नई offers उपलब्ध होने पर वे यहाँ दिखेंगी।" else "Published course offers will appear here when available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        offers.forEach { offer ->
            val id = offer.optLong("course_id", offer.optLong("courseId"))
            val title = offer.optString("title").ifBlank { offer.optString("course_name", "Course") }
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
                            Text(offer.optString("curriculum_version").ifBlank { offer.optString("course_code") },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            when {
                                owned -> if (hi) "आपका course" else "Owned"
                                pricePaise > 0L && currency == "INR" -> inr.format(pricePaise / 100.0)
                                pricePaise == 0L -> if (hi) "मुफ़्त" else "Free"
                                else -> if (hi) "अभी उपलब्ध नहीं" else "Unavailable"
                            },
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
                        )
                    }
                    offer.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
                        pricePaise > 0L && currency == "INR" -> Button(
                            onClick = { onBuy(id) }, enabled = !busy && id > 0L && busyCourseId != id, modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busyCourseId == id) { if (hi) "Checkout खुल रहा है…" else "Opening checkout…" }
                                else if (hi) "Razorpay से खरीदें" else "Buy with Razorpay")
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
}
