package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CourseInvoiceHistoryScreen(
    invoices: List<JSONObject>,
    workspace: String,
    language: String,
    loading: Boolean,
    loadFailed: Boolean,
    busyDocumentId: Long?,
    onRefresh: () -> Unit,
    onOpenPdf: (Long, Long, String) -> Unit,
    onSavePdf: (Long, Long, String) -> Unit,
    onSharePdf: (Long, Long, String) -> Unit,
    onBack: () -> Unit
) {
    val hi = language == "hi"
    val parent = workspace == "PARENT"
    val money = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val dateLocale = if (hi) Locale("hi", "IN") else Locale("en", "IN")
    val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", dateLocale)

    fun text(row: JSONObject, key: String, fallback: String = ""): String =
        row.optString(key, fallback).takeIf { it.isNotBlank() && it != "null" } ?: fallback

    fun courseType(code: String): String = when (code.trim().uppercase(Locale.ROOT)) {
        "K12" -> if (hi) "विद्यालयी पढ़ाई" else "K–12 learning"
        "COMPETITIVE" -> if (hi) "प्रतियोगी परीक्षा तैयारी" else "Competitive preparation"
        "ASSESSMENT" -> if (hi) "परीक्षा एवं टेस्ट" else "Assessment & tests"
        else -> ""
    }

    fun amount(row: JSONObject, key: String): String =
        money.format((row.optLong(key, 0L).coerceAtLeast(0L)) / 100.0)

    fun issuedDate(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
        }.getOrElse { raw.substringBefore('T') }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (hi) "इनवॉइस और भुगतान" else "Invoices & payments",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (parent) {
                        if (hi) "आपकी खरीद का इनवॉइस; साथ में उस विद्यार्थी का नाम भी दिखेगा जिसके लिए कोर्स खरीदा गया।"
                        else "Invoices for purchases made by you, with the learner who receives the course."
                    } else {
                        if (hi) "सत्यापित कोर्स भुगतान का PDF खोलें, सेव करें या शेयर करें।"
                        else "Open, save, or share the PDF for each verified course purchase."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onBack) { Text(if (hi) "वापस" else "Back") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f)) {
                Text(if (hi) "रीफ़्रेश" else "Refresh")
            }
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Text(if (hi) "इनवॉइस लोड हो रहे हैं…" else "Loading invoices…")
            }
        } else if (loadFailed) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hi) "इनवॉइस लोड नहीं हो सके" else "Invoices couldn't be loaded",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (hi) "कनेक्शन जाँचें और फिर प्रयास करें।" else "Check the connection and retry.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hi) "फिर प्रयास करें" else "Retry")
                    }
                }
            }
        } else if (invoices.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (hi) "अभी कोई इनवॉइस नहीं है" else "No invoices yet",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hi) "भुगतान सर्वर द्वारा सत्यापित होने और इनवॉइस जारी होने पर वह यहाँ दिखाई देगा।"
                        else "After a course payment is verified and its invoice is issued, it will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            invoices.forEach { invoice ->
                val orderId = invoice.optLong("course_order_id", 0L)
                val documentId = invoice.optLong("invoice_document_id", 0L)
                val documentNumber = text(invoice, "document_number", "invoice-$documentId")
                val type = courseType(text(invoice, "course_type_code"))
                val courseTitle = text(invoice, "course_title", if (hi) "कोर्स खरीद" else "Course purchase")
                val beneficiary = text(invoice, "beneficiary_name")
                val documentType = text(invoice, "document_type")
                val isCredit = documentType == "CREDIT_NOTE"
                val isDebit = documentType == "DEBIT_NOTE"
                val busy = busyDocumentId == documentId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(documentNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(courseTitle, style = MaterialTheme.typography.bodyLarge)
                        if (type.isNotBlank() || text(invoice, "course_code").isNotBlank()) {
                            Text(
                                listOf(type, text(invoice, "course_code")).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (parent && beneficiary.isNotBlank()) {
                            Text(
                                (if (hi) "विद्यार्थी: " else "Learner: ") + beneficiary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            listOf(issuedDate(text(invoice, "issued_at")),
                                (if (hi) "टैक्स " else "Tax ") + amount(invoice, "tax_value_paise")).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(amount(invoice, "total_value_paise"),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    when {
                                        isCredit -> if (hi) "क्रेडिट नोट" else "Credit note"
                                        isDebit -> if (hi) "डेबिट नोट" else "Debit note"
                                        else -> if (hi) "भुगतान हुआ" else "Paid"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(if (hi) "भुगतान: " + amount(invoice, "amount_paid_paise")
                                else "Paid: " + amount(invoice, "amount_paid_paise"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { onOpenPdf(orderId, documentId, documentNumber) },
                                enabled = !busy && orderId > 0L && documentId > 0L,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (busy) { if (hi) "तैयार हो रहा है…" else "Preparing…" } else if (hi) "PDF खोलें" else "Open PDF") }
                            OutlinedButton(
                                onClick = { onSavePdf(orderId, documentId, documentNumber) },
                                enabled = !busy && orderId > 0L && documentId > 0L,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (hi) "सेव करें" else "Save PDF") }
                            TextButton(
                                onClick = { onSharePdf(orderId, documentId, documentNumber) },
                                enabled = !busy && orderId > 0L && documentId > 0L
                            ) { Text(if (hi) "शेयर" else "Share") }
                        }
                    }
                }
            }
        }
    }
}
