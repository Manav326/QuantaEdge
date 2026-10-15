package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentTextbookQuickLinks(
    documents: List<JSONObject>,
    language: String,
    busy: Boolean,
    onOpenDocument: (JSONObject) -> Unit,
    onBrowseSubject: (String) -> Unit
) {
    val hi = language == "hi"
    val subjects = listOf("maths", "science")
    val books = documents.filter { it.optString("scope") == "SUBJECT_BOOK" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (hi) "आपकी किताबें" else "Your books", style = MaterialTheme.typography.titleMedium)
        if (documents.isEmpty()) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (hi) "किताबें लोड हो रही हैं…" else "Loading textbooks…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (hi) "अभी कोई textbook PDF उपलब्ध नहीं है" else "No textbook PDFs available yet",
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hi) "विषय चुनकर किताबों और अध्यायों की उपलब्धता दोबारा जाँचें।"
                            else "Choose a subject to check book and chapter availability again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onBrowseSubject("maths") }, enabled = !busy) {
                                Text(if (hi) "गणित देखें" else "Browse Maths")
                            }
                            OutlinedButton(onClick = { onBrowseSubject("science") }, enabled = !busy) {
                                Text(if (hi) "विज्ञान देखें" else "Browse Science")
                            }
                        }
                    }
                }
            }
        } else Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            subjects.forEach { code ->
                val book = books.firstOrNull { it.optString("subject_code") == code }
                val chapters = documents.count {
                    it.optString("scope") == "CHAPTER_PDF" && it.optString("subject_code") == code
                }
                Card {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (code == "maths") {
                            if (hi) "गणित" else "Mathematics"
                        } else {
                            if (hi) "विज्ञान" else "Science"
                        }, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (book != null) localizedUiText("Book PDF · ") + book.optInt("page_count") + " " + localizedUiText("pages")
                            else "$chapters " + localizedUiText("chapter PDFs"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (book != null) {
                            Button(onClick = { onOpenDocument(book) }, enabled = !busy) {
                                Text(if (hi) "किताब खोलें" else "Open book")
                            }
                        } else {
                            OutlinedButton(onClick = { onBrowseSubject(code) }, enabled = !busy) {
                                Text(if (hi) "अध्याय देखें" else "View chapters")
                            }
                        }
                        if (book != null && chapters > 0) {
                            TextButton(onClick = { onBrowseSubject(code) }, enabled = !busy) {
                                Text("$chapters " + localizedUiText("chapter PDFs"))
                            }
                        }
                    }
                }
            }
        }
    }
}
