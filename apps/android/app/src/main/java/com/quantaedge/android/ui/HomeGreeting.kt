package com.quantaedge.android.ui

/** Pure greeting rules so time ranges and both supported languages can be verified. */
internal object HomeGreeting {
    fun english(hour: Int): String = when (hour.coerceIn(0, 23)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }

    fun hindi(hour: Int): String = when (hour.coerceIn(0, 23)) {
        in 5..11 -> "सुप्रभात"
        in 12..16 -> "शुभ दोपहर"
        in 17..20 -> "शुभ संध्या"
        else -> "शुभ रात्रि"
    }

    fun text(hour: Int, language: String): String =
        if (language.trim().equals("en", ignoreCase = true) ||
            language.trim().equals("english", ignoreCase = true)) {
            english(hour)
        } else {
            hindi(hour)
        }
}
