package com.quantaedge.android.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal val LocalQuantaLanguage = staticCompositionLocalOf { "hi" }

private data class UiPhrase(val key: String, val english: String, val hindi: String)
private val uiPhrases = listOf(
    UiPhrase("nav_home", "Home", "होम"),
    UiPhrase("nav_learn", "Learn", "सीखें"),
    UiPhrase("nav_tests", "Tests", "टेस्ट"),
    UiPhrase("nav_results", "Results", "परिणाम"),
    UiPhrase("nav_profile", "Profile", "प्रोफ़ाइल"),
    UiPhrase("refresh", "Refresh", "रिफ्रेश"),
    UiPhrase("sign_out", "Sign out", "साइन आउट"),
    UiPhrase("parent_dashboard", "Parent dashboard", "अभिभावक डैशबोर्ड"),
    UiPhrase("active_children", "Active students: ", "सक्रिय छात्र: "),
    UiPhrase("student_profile_create", "Create student profile", "छात्र प्रोफ़ाइल बनाएँ"),
    UiPhrase("student_profile_add", "Add child profile", "नया छात्र प्रोफ़ाइल बनाएँ"),
    UiPhrase("student_name", "Student full name", "छात्र का पूरा नाम"),
    UiPhrase("student_access", "Student access", "छात्र access"),
    UiPhrase("class_prefix", "Class ", "कक्षा "),
    UiPhrase("class_label", "Class", "कक्षा"),
    UiPhrase("class_colon", "Class: ", "कक्षा: "),
    UiPhrase("subject_math", "Mathematics", "गणित"),
    UiPhrase("subject_science", "Science", "विज्ञान"),
    UiPhrase("subjects_title", "Learning subjects", "पढ़ने के विषय"),
    UiPhrase("login_parent_create", "Create parent account", "नया अभिभावक खाता बनाएँ"),
    UiPhrase("login_forgot", "Forgot password?", "पासवर्ड भूल गए?"),
    UiPhrase("login_start", "Start learning", "सीखना शुरू करें"),
    UiPhrase("login_parent_mobile", "Parent mobile number", "अभिभावक का मोबाइल"),
    UiPhrase("login_registered_mobile", "Registered parent mobile", "अभिभावक का पंजीकृत मोबाइल"),
    UiPhrase("login_username", "Student username", "छात्र यूज़रनेम"),
    UiPhrase("password", "Password", "पासवर्ड"),
    UiPhrase("sign_in", "Sign in", "साइन इन करें"),
    UiPhrase("signing_in", "Signing in…", "साइन इन हो रहा है…"),
    UiPhrase("parent_account", "Parent", "अभिभावक"),
    UiPhrase("student_account", "Student", "छात्र"),
    UiPhrase("otp_mobile", "Mobile number", "मोबाइल नंबर"),
    UiPhrase("otp_full_name", "Full name", "पूरा नाम"),
    UiPhrase("otp_password", "New password (at least 8 characters)", "नया पासवर्ड (कम से कम 8 अक्षर)"),
    UiPhrase("otp_send", "Send OTP", "OTP भेजें"),
    UiPhrase("otp_change_mobile", "Change mobile number", "मोबाइल नंबर बदलें"),
    UiPhrase("otp_reset_title", "Reset password", "पासवर्ड रीसेट करें"),
    UiPhrase("otp_create_title", "Create parent account", "अभिभावक खाता बनाएँ"),
    UiPhrase("otp_verify_create", "Verify and create account", "खाता सत्यापित कर बनाएँ"),
    UiPhrase("otp_update_password", "Update password", "पासवर्ड अपडेट करें"),
    UiPhrase("otp_signup_help", "Verify your mobile number to create your parent account.", "मोबाइल नंबर सत्यापित करके अपना अभिभावक खाता बनाएँ।"),
    UiPhrase("otp_reset_help", "Request an OTP on your registered mobile and set a new password.", "अपने पंजीकृत मोबाइल पर OTP प्राप्त करके नया पासवर्ड सेट करें।"),
    UiPhrase("development_otp", "Development OTP: ", "स्थानीय विकास OTP: "),
    UiPhrase("next_lesson", "Your next lesson", "आपके लिए अगला पाठ"),
    UiPhrase("open_next_lesson", "Open next lesson", "अगला पाठ खोलें"),
    UiPhrase("diagnostic_nudge", "Complete the diagnostic to personalize your next steps.", "अपनी अगली सीख तय करने के लिए प्रारंभिक जाँच पूरी करें।"),
    UiPhrase("lessons_practice", "View lessons and practice →", "पाठ और अभ्यास देखें →"),
    UiPhrase("open_lesson", "Open lesson →", "पाठ खोलें →"),
    UiPhrase("practice_session", "Practice session", "अभ्यास सत्र"),
    UiPhrase("session_prefix", "Session: ", "सत्र: "),
    UiPhrase("time_prefix", "Time: ", "समय: "),
    UiPhrase("question_prefix", "Question: ", "प्रश्न: "),
    UiPhrase("question_number_prefix", "Question ", "प्रश्न "),
    UiPhrase("attempt_prefix", "Attempt ", "प्रयास "),
    UiPhrase("attempts_prefix", "Attempts: ", "प्रयास: "),
    UiPhrase("score_prefix", "Score: ", "अंक: "),
    UiPhrase("correct_answer_prefix", "Correct answer: ", "सही उत्तर: "),
    UiPhrase("teacher_feedback_prefix", "Teacher feedback: ", "शिक्षक की टिप्पणी: "),
    UiPhrase("teacher_prefix", "Teacher: ", "शिक्षक: "),
    UiPhrase("latest_score_prefix", "Latest score: ", "नवीनतम अंक: "),
    UiPhrase("best_recent_scores_prefix", "Best of last three: ", "पिछले तीन में सर्वश्रेष्ठ: "),
    UiPhrase("released_attempts_prefix", "Total released attempts: ", "कुल जारी attempts: "),
    UiPhrase("page_prefix", "Page ", "पृष्ठ "),
    UiPhrase("username_prefix", "Username: ", "यूज़रनेम: "),
    UiPhrase("account_prefix", "Account: ", "खाता: "),
    UiPhrase("app_language_prefix", "App language: ", "ऐप भाषा: "),
    UiPhrase("status_prefix", "Status: ", "स्थिति: "),
    UiPhrase("attendance_prefix", "Attendance: ", "Attendance: "),
    UiPhrase("diagnostic", "Diagnostic", "प्रारंभिक जाँच"),
    UiPhrase("diagnostic_submit", "Submit diagnostic", "जाँच जमा करें"),
    UiPhrase("check_again", "Check again", "दोबारा जाँचें"),
    UiPhrase("diagnostic_refresh", "Refresh status", "स्थिति रिफ्रेश करें"),
    UiPhrase("assessment_title", "Tests and assessments", "टेस्ट और मूल्यांकन"),
    UiPhrase("my_results", "My results", "मेरे परिणाम"),
    UiPhrase("refresh_results", "Refresh result status", "परिणाम की स्थिति रिफ्रेश करें"),
    UiPhrase("practice_again", "Retry incorrect auto-graded answers; submitted unresolved answers cannot be resubmitted.", "गलत auto-graded उत्तर पर फिर प्रयास कर सकते हैं। जमा हुआ अनिर्णीत उत्तर दोबारा नहीं भेजा जा सकता।"),
    UiPhrase("assessment_submitted", "Test submitted. Result releases after written answers are reviewed.", "टेस्ट जमा है। लिखित उत्तरों का मूल्यांकन होने पर परिणाम जारी होगा।"),
    UiPhrase("explanation", "Explanation", "व्याख्या"),
    UiPhrase("offline_test", "Protected offline test", "संरक्षित offline test"),
    UiPhrase("offline_start", "Start offline test (timer starts now)", "Offline test शुरू करें (timer अभी शुरू होगा)"),
    UiPhrase("offline_answers", "Answers are encrypted and stored on this device.", "उत्तर इस device पर encrypted सुरक्षित हैं"),
    UiPhrase("offline_info", "The offline timer starts immediately. Answers remain encrypted on this device; no local score is shown. When online, the server syncs and grades answers. This is only for staff-approved low-stakes tests.", "Offline mode शुरू करते ही test का timer शुरू हो जाता है। Answers इस device पर encrypted रहते हैं; कोई local score नहीं दिखाया जाता। Internet मिलने पर server answers sync करके जाँचता है। यह सुविधा केवल staff-approved low-stakes tests के लिए है।"),
    UiPhrase("offline_sync_info", "These answers are not graded here. When internet returns, the authenticated server syncs and grades them. The offline package can sync until 72 hours after the timer expires.", "इन answers को यहाँ grade नहीं किया जा रहा। Internet मिलने पर authenticated server उन्हें sync करके जाँचेगा। Offline package की sync सीमा timer खत्म होने के 72 घंटे बाद तक है।"),
    UiPhrase("remove_offline_title", "Remove offline copy?", "Offline copy हटाएँ?"),
    UiPhrase("remove_offline", "Remove offline copy", "Offline copy हटाएँ"),
    UiPhrase("remove_download", "Remove downloaded copy", "Downloaded copy हटाएँ"),
    UiPhrase("cancel", "Cancel", "रद्द करें"),
    UiPhrase("resource_library", "Textbook Library", "Textbook Library"),
    UiPhrase("resource_help", "Read materials inside the app. Offline downloads are not available in public Downloads or other apps.", "सामग्री ऐप के अंदर पढ़ें। ऑफ़लाइन डाउनलोड पब्लिक Downloads या दूसरी ऐप में उपलब्ध नहीं होगा।"),
    UiPhrase("resource_privacy", "This content is shown only inside QuantaEdge; export and sharing are unavailable.", "यह सामग्री केवल QuantaEdge ऐप में दिखाई जाती है; export और share विकल्प उपलब्ध नहीं हैं।"),
    UiPhrase("read", "Read", "पढ़ें"),
    UiPhrase("offline_download", "Offline download", "ऑफ़लाइन डाउनलोड"),
    UiPhrase("last_page", "Previous ←", "← पिछला"),
    UiPhrase("next_page", "Next →", "अगला →"),
    UiPhrase("visual_loading", "Loading visual…", "Visual लोड हो रहा है…"),
    UiPhrase("child_archive_title", "Archive this student profile?", "छात्र प्रोफ़ाइल archive करें?"),
    UiPhrase("child_archive", "Archive student profile", "छात्र प्रोफ़ाइल archive करें"),
    UiPhrase("archive", "Archive", "Archive करें"),
    UiPhrase("child_credentials", "Change login credentials", "Login credentials बदलें"),
    UiPhrase("login_password_hint", "Login password (at least 8 characters)", "Login password (कम-से-कम 8 अक्षर)"),
    UiPhrase("login_username_hint", "Login username (3–32 characters)", "Login username (3–32 अक्षर)"),
    UiPhrase("save_credentials", "Save credentials", "Credentials सुरक्षित करें"),
    UiPhrase("subject_access", "Subject access", "विषय access"),
    UiPhrase("save_subjects", "Save subject changes", "विषय परिवर्तन सुरक्षित करें"),
    UiPhrase("password_security", "For password security, the current password is not shown again in the app.", "पासवर्ड सुरक्षा के लिए वर्तमान पासवर्ड ऐप में वापस नहीं दिखाया जाता।"),
    UiPhrase("archive_notice", "This disables access to the student account. If another guardian has active consent, their access may remain under backend rules.", "यह छात्र खाते की access बंद कर देगा। यदि किसी दूसरे अभिभावक की active consent है, तो उनकी access backend के नियमों के अनुसार बनी रह सकती है।"),
    UiPhrase("child_create_notice", "New student accounts are currently created for Hindi-medium learning. Parent consent is required.", "अभी नए छात्र खाते हिंदी माध्यम के लिए बनाए जाते हैं। अभिभावक की सहमति आवश्यक है।"),
    UiPhrase("guardian_consent", "I am the parent and consent to creating this student account and monitoring learning progress.", "मैं अभिभावक हूँ और इस छात्र खाते के निर्माण व सीखने की प्रगति के लिए सहमति देता/देती हूँ।"),
    UiPhrase("photo_pick_notice", "Only a photo is selected. No broad storage permission is requested; the secured JPEG is sent to your QuantaEdge account.", "केवल फोटो चुनी जाती है। कोई broad storage permission नहीं माँगी जाती; सुरक्षित JPEG को आपके QuantaEdge खाते में भेजा जाता है।"),
    UiPhrase("photo_new", "New photo selected", "नई फोटो चुनी गई"),
    UiPhrase("photo_current", "Current photo", "वर्तमान फोटो"),
    UiPhrase("downloaded_materials_privacy", "Downloaded learning materials stay encrypted in the app private storage and are excluded from Android backup.", "डाउनलोड की गई अध्ययन सामग्री ऐप के निजी स्टोरेज में encrypted रहती है और Android backup में शामिल नहीं होती।"),
    UiPhrase("profile_settings", "Profile and settings", "प्रोफ़ाइल और सेटिंग"),
    UiPhrase("profile_title", "Profile and learning preferences", "प्रोफ़ाइल और सीखने की पसंद"),
    UiPhrase("profile_remove_photo", "Remove profile photo", "प्रोफ़ाइल फ़ोटो हटाएँ"),
    UiPhrase("profile_name", "Name", "नाम"),
    UiPhrase("profile_city", "City or town (optional)", "शहर / कस्बा (वैकल्पिक)"),
    UiPhrase("profile_state", "State (optional)", "राज्य (वैकल्पिक)"),
    UiPhrase("profile_school", "School (optional)", "विद्यालय (वैकल्पिक)"),
    UiPhrase("profile_school_medium", "School medium", "विद्यालय की भाषा"),
    UiPhrase("profile_choose_medium", "Select school medium", "विद्यालय की भाषा चुनें"),
    UiPhrase("profile_favourite_subject", "Favourite subject", "पसंदीदा विषय"),
    UiPhrase("profile_choose_subject", "Select favourite subject", "पसंदीदा विषय चुनें"),
    UiPhrase("profile_content_language", "Learning content language", "पढ़ाई की सामग्री की भाषा"),
    UiPhrase("profile_goal", "My learning goal", "मेरा सीखने का लक्ष्य"),
    UiPhrase("profile_save", "Save profile", "प्रोफ़ाइल सुरक्षित करें"),
    UiPhrase("profile_email", "Email (optional)", "ईमेल (वैकल्पिक)"),
    UiPhrase("profile_mobile", "Mobile", "मोबाइल"),
    UiPhrase("profile_occupation", "Occupation", "पेशा"),
    UiPhrase("profile_organization", "Organization (optional)", "संस्था (वैकल्पिक)"),
    UiPhrase("profile_parent_language", "Preferred learning language", "पढ़ाई की पसंदीदा भाषा"),
    UiPhrase("profile_language_select", "Select language", "भाषा चुनें"),
    UiPhrase("progress_title", "My learning progress", "मेरी सीखने की प्रगति"),
    UiPhrase("progress_lessons", "Lessons completed", "पाठ पूरे"),
    UiPhrase("progress_accuracy", "Practice accuracy", "अभ्यास सटीकता"),
    UiPhrase("progress_concept_mastery", "Concept mastery", "अवधारणा महारत"),
    UiPhrase("progress_concepts", "Concepts to revisit", "जिन concepts पर फिर काम कर सकते हैं"),
    UiPhrase("progress_no_mastery", "No concept mastery is available yet. It will appear after practicing questions from published lessons.", "अभी concept mastery उपलब्ध नहीं है। प्रकाशित पाठ के प्रश्नों का अभ्यास करने पर यहाँ दिखाई देगी।"),
    UiPhrase("progress_no_tests", "No test attempts yet.", "अभी कोई टेस्ट attempt नहीं है।"),
    UiPhrase("progress_subjects", "Subject progress", "विषयवार प्रगति"),
    UiPhrase("progress_sessions", "Learning sessions", "learning sessions"),
    UiPhrase("progress_minutes", "Minutes tracked", "मिनट tracked"),
    UiPhrase("progress_status", "Status", "स्थिति"),
    UiPhrase("progress_score", "Score", "अंक"),
    UiPhrase("live_classes", "Live classes and recordings", "Live classes और recordings"),
    UiPhrase("live_schedule", "Live schedule", "Live schedule"),
    UiPhrase("recordings", "Recordings", "Recordings"),
    UiPhrase("class_leave", "Leave class", "Class leave करें"),
    UiPhrase("class_join", "Join live class", "Live class join करें"),
    UiPhrase("class_recording", "A published recording for this class is also available.", "इस class की published recording भी उपलब्ध है।"),
    UiPhrase("recording_position", "Playback position: ", "Playback position: "),
    UiPhrase("recording_saved", "Progress is saved to your QuantaEdge account periodically.", "Progress is saved to your QuantaEdge account periodically."),
    UiPhrase("recording_save_back", "Save progress and go back", "प्रगति सुरक्षित कर वापस जाएँ"),
    UiPhrase("tutor_title", "QuantaEdge Tutor", "QuantaEdge Tutor"),
    UiPhrase("tutor_hint", "Start with a hint", "पहले Hint से शुरू करें"),
    UiPhrase("tutor_help", "The tutor uses this lesson’s published material and your learning context to help.", "Tutor इसी पाठ की प्रकाशित सामग्री और आपकी learning context के आधार पर मदद करेगा।"),
    UiPhrase("tutor_stuck", "Where are you stuck?", "आप कहाँ अटके हैं?"),
    UiPhrase("widget_select_all", "Select all that apply.", "सभी लागू विकल्प चुनें।"),
    UiPhrase("widget_order", "Arrange the items below in the correct order. Use ↑ / ↓ to change order.", "नीचे दिए गए items को सही क्रम में रखें। ↑ / ↓ से क्रम बदलें।"),
    UiPhrase("widget_match_json", "Enter matches as a JSON map, e.g. {\"1\":\"A\",\"2\":\"B\"}.", "मिलान को JSON map के रूप में लिखें, जैसे {\"1\":\"A\",\"2\":\"B\"}."),
    UiPhrase("widget_match_answers", "Matching answers", "मिलान के उत्तर"),
    UiPhrase("widget_number", "Enter your numerical answer", "अपना संख्यात्मक उत्तर लिखें"),
    UiPhrase("widget_order_json", "Enter the order as a JSON array, e.g. [\"B\",\"C\",\"A\"].", "क्रम को JSON array के रूप में लिखें, जैसे [\"B\",\"C\",\"A\"]."),
    UiPhrase("parent_active_prefix", "Active children: ", "सक्रिय छात्र: "),
    UiPhrase("lessons_completed_prefix", "Lessons completed: ", "पूरे किए गए पाठ: "),
    UiPhrase("attempt_label_prefix", "Attempt: ", "प्रयास: ")
)
private val uiPhraseBySource: Map<String, UiPhrase> = buildMap {
    uiPhrases.forEach { phrase ->
        putIfAbsent(phrase.english, phrase)
        putIfAbsent(phrase.hindi, phrase)
    }
}

/** Select a translated resource using the locale chosen inside the app, independently of Android device locale. */
@Composable
internal fun localizedUiText(source: String): String {
    val context = LocalContext.current
    val language = LocalQuantaLanguage.current
    val phrase = uiPhraseBySource[source] ?: uiPhrases
        .filter { it.hindi.endsWith(" ") || it.english.endsWith(" ") }
        .sortedByDescending { maxOf(it.hindi.length, it.english.length) }
        .firstOrNull { source.startsWith(it.hindi) || source.startsWith(it.english) }
    if (phrase == null) return source
    val exact = source == phrase.hindi || source == phrase.english
    val suffix = if (exact) "" else when {
        source.startsWith(phrase.hindi) -> source.removePrefix(phrase.hindi)
        source.startsWith(phrase.english) -> source.removePrefix(phrase.english)
        else -> ""
    }
    val resourceId = context.resources.getIdentifier("qe_" + phrase.key, "string", context.packageName)
    if (resourceId == 0) return source
    val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(if (language == "hi") "hi" else "en"))
    }
    val localizedContext: Context = context.createConfigurationContext(configuration)
    return localizedContext.getString(resourceId) + suffix
}
