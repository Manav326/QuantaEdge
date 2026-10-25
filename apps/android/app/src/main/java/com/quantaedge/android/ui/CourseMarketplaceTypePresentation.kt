package com.quantaedge.android.ui

/**
 * Shared learner-facing course-type labels for the student and family Marketplace.
 * Course types describe different hierarchies; they must not be inferred from course titles.
 */
internal fun marketplaceCourseTypeLabel(courseTypeCode: String, hindi: Boolean): String =
    when (courseTypeCode.trim().uppercase()) {
        "K12" -> if (hindi) "विद्यालयी पढ़ाई" else "K–12 learning"
        "COMPETITIVE" -> if (hindi) "प्रतियोगी परीक्षा तैयारी" else "Competitive preparation"
        "ASSESSMENT" -> if (hindi) "परीक्षा एवं टेस्ट" else "Assessment & tests"
        else -> ""
    }

internal fun marketplaceAssessmentInventorySummary(
    hindi: Boolean,
    publishedPacks: Int,
    publishedTests: Int,
    publishedQuestions: Int
): String {
    val packs = publishedPacks.coerceAtLeast(0)
    val tests = publishedTests.coerceAtLeast(0)
    val questions = publishedQuestions.coerceAtLeast(0)
    return if (hindi) {
        "$packs test packs · $tests टेस्ट · $questions प्रश्न"
    } else {
        "$packs test packs · $tests tests · $questions questions"
    }
}
