package com.quantaedge.android.ui

/**
 * Navigation ownership rules shared by the Compose shell and unit tests.
 * Keep route grouping independent of rendering so new routes can be covered
 * without requiring a UI/Compose test harness.
 */
internal enum class AppRoute {
    WELCOME, LOGIN, PARENT_SIGNUP, PARENT_RESET, STUDENT_HOME, ALL_COURSES, MARKETPLACE, PARENT_HOME, PARENT_REPORT, PARENT_PROFILE,
    PARENT_CHILD_CREATE, PARENT_CHILD_MANAGE, PARENT_CHILD_ARCHIVED, ACCOUNT_LIFECYCLE, SUBJECT_LESSONS, LESSON, PRACTICE,
    DIAGNOSTIC, ASSESSMENTS, ASSESSMENT_ATTEMPT, ASSESSMENT_RESULTS, ASSESSMENT_REVIEW, RESOURCES,
    DOCUMENT_READER, PROFILE, PROGRESS, TUTOR, CLASSES, RECORDING_PLAYER, CERTIFICATES, LEADERBOARD, ALL_COURSES, MARKETPLACE
}

internal object StudentNavigationContract {
    val studentGroupIds: List<String> = listOf("today", "learn", "practice", "progress", "library")
    val parentGroupIds: List<String> = listOf("family", "children", "profile")

    fun usesLeftRail(isLandscape: Boolean): Boolean = isLandscape

    fun studentGroupForRoute(destination: AppRoute, lastStudentNavGroup: String): String = when (destination) {
        AppRoute.STUDENT_HOME, AppRoute.ALL_COURSES, AppRoute.MARKETPLACE -> "today"
        AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.TUTOR, AppRoute.CLASSES,
        AppRoute.RECORDING_PLAYER -> "learn"
        AppRoute.PRACTICE, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT,
        AppRoute.ASSESSMENT_RESULTS, AppRoute.ASSESSMENT_REVIEW, AppRoute.LEADERBOARD -> "practice"
        AppRoute.PROGRESS, AppRoute.CERTIFICATES -> "progress"
        AppRoute.RESOURCES, AppRoute.DOCUMENT_READER -> "library"
        AppRoute.PROFILE, AppRoute.ACCOUNT_LIFECYCLE -> lastStudentNavGroup.takeIf { it in studentGroupIds } ?: "today"
        // Authentication and parent-only routes do not participate in student navigation.
        // Keep them explicit so adding a new AppRoute requires an intentional ownership decision.
        AppRoute.WELCOME, AppRoute.LOGIN, AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET,
        AppRoute.PARENT_HOME, AppRoute.PARENT_REPORT, AppRoute.PARENT_PROFILE,
        AppRoute.PARENT_CHILD_CREATE, AppRoute.PARENT_CHILD_MANAGE, AppRoute.PARENT_CHILD_ARCHIVED -> "today"
    }

    fun parentGroupForRoute(destination: AppRoute, selectedParentGroup: String): String = when (destination) {
        AppRoute.PARENT_PROFILE -> "profile"
        AppRoute.PARENT_CHILD_CREATE, AppRoute.PARENT_CHILD_MANAGE, AppRoute.PARENT_CHILD_ARCHIVED -> "children"
        AppRoute.PARENT_HOME, AppRoute.PARENT_REPORT ->
            selectedParentGroup.takeIf { it in parentGroupIds } ?: "family"
        // These routes are outside parent navigation and are only passed while the
        // top-level shell is composing around sign-in or a role transition.
        AppRoute.WELCOME, AppRoute.LOGIN, AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET, AppRoute.STUDENT_HOME, AppRoute.ALL_COURSES, AppRoute.MARKETPLACE,
        AppRoute.COURSES, AppRoute.MARKETPLACE, AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.PRACTICE, AppRoute.DIAGNOSTIC,
        AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT, AppRoute.ASSESSMENT_RESULTS,
        AppRoute.ASSESSMENT_REVIEW, AppRoute.RESOURCES, AppRoute.DOCUMENT_READER,
        AppRoute.PROFILE, AppRoute.ACCOUNT_LIFECYCLE, AppRoute.PROGRESS, AppRoute.TUTOR, AppRoute.CLASSES,
        AppRoute.RECORDING_PLAYER, AppRoute.CERTIFICATES, AppRoute.LEADERBOARD ->
            selectedParentGroup.takeIf { it in parentGroupIds } ?: "family"
    }
}
