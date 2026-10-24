package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class StudentNavigationContractTest {
    @Test
    fun relationalStructuredLessonsAreRoutedByTheirExactDatabaseId() {
        assertEquals(42L, databaseLessonIdFromArtifactId("db-lesson-42"))
        assertEquals(1L, databaseLessonIdFromArtifactId("db-lesson-1"))

        listOf(
            "",
            "db-lesson-0",
            "db-lesson-01",
            "db-lesson-x",
            "db-q-42",
            "artifact-v1",
            "db-lesson-999999999999999999999999"
        ).forEach { artifactId ->
            assertEquals(null, databaseLessonIdFromArtifactId(artifactId))
        }
    }

    @Test
    fun canonicalGroupsMatchTheWebOrder() {
        assertEquals(listOf("today", "courses", "learn", "practice", "progress", "library"), StudentNavigationContract.studentGroupIds)
        assertEquals(listOf("family", "children", "profile"), StudentNavigationContract.parentGroupIds)
    }

    @Test
    fun todayOpensStudentHomeAndLessonAndClassRoutesBelongToLearn() {
        assertEquals("today", StudentNavigationContract.studentGroupForRoute(AppRoute.STUDENT_HOME, "learn"))
        listOf(AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.TUTOR, AppRoute.CLASSES, AppRoute.RECORDING_PLAYER)
            .forEach { route -> assertEquals("learn", StudentNavigationContract.studentGroupForRoute(route, "today")) }
    }

    @Test
    fun courseCatalogueAndMarketplaceReturnToTheStudentWorkspace() {
        assertEquals("courses", StudentNavigationContract.studentGroupForRoute(AppRoute.ALL_COURSES, "learn"))
        assertEquals("courses", StudentNavigationContract.studentGroupForRoute(AppRoute.MARKETPLACE, "learn"))
    }

    @Test
    fun practiceTestsResultsAndLeaderboardStayTogether() {
        listOf(
            AppRoute.PRACTICE, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT,
            AppRoute.ASSESSMENT_RESULTS, AppRoute.ASSESSMENT_REVIEW, AppRoute.LEADERBOARD
        ).forEach { route -> assertEquals("practice", StudentNavigationContract.studentGroupForRoute(route, "today")) }
    }

    @Test
    fun noCourseEmptyStateWaitsForAConfirmedSuccessfulCourseLoad() {
        assertFalse(hasConfirmedNoEnrolledCourses(
            courseListLoaded = false, courseListLoadFailed = false, hasCourseAccess = false
        ))
        assertFalse(hasConfirmedNoEnrolledCourses(
            courseListLoaded = true, courseListLoadFailed = true, hasCourseAccess = false
        ))
        assertFalse(hasConfirmedNoEnrolledCourses(
            courseListLoaded = true, courseListLoadFailed = false, hasCourseAccess = true
        ))
        assertTrue(hasConfirmedNoEnrolledCourses(
            courseListLoaded = true, courseListLoadFailed = false, hasCourseAccess = false
        ))
    }

    @Test
    fun progressCertificatesAndLibraryRoutesKeepTheirOwnGroups() {
        listOf(AppRoute.PROGRESS, AppRoute.CERTIFICATES).forEach { route ->
            assertEquals("progress", StudentNavigationContract.studentGroupForRoute(route, "today"))
        }
        listOf(AppRoute.RESOURCES, AppRoute.DOCUMENT_READER).forEach { route ->
            assertEquals("library", StudentNavigationContract.studentGroupForRoute(route, "today"))
        }
    }

    @Test
    fun profileReturnsToTheLastValidStudentGroup() {
        StudentNavigationContract.studentGroupIds.forEach { group ->
            assertEquals(group, StudentNavigationContract.studentGroupForRoute(AppRoute.PROFILE, group))
        }
        assertEquals("today", StudentNavigationContract.studentGroupForRoute(AppRoute.PROFILE, "not-a-group"))
    }

    @Test
    fun welcomeArchivedAndAccountRoutesHaveExplicitNavigationOwnership() {
        assertEquals("today", StudentNavigationContract.studentGroupForRoute(AppRoute.WELCOME, "today"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_CHILD_ARCHIVED, "family"))
        assertEquals("progress", StudentNavigationContract.studentGroupForRoute(AppRoute.ACCOUNT_LIFECYCLE, "progress"))
    }

    @Test
    fun parentRoutesRemainRoleSpecific() {
        assertEquals("family", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_HOME, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_CHILD_CREATE, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_CHILD_MANAGE, "family"))
        assertEquals("profile", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_PROFILE, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_REPORT, "children"))
        assertEquals("family", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_REPORT, "invalid"))
    }

    @Test
    fun assessmentCourseResolverUsesServerTypeAndDeliveryMode() {
        assertTrue(isAssessmentOnlyCourse(JSONObject("""{"course_type_code":"ASSESSMENT"}""")))
        assertTrue(isAssessmentOnlyCourse(JSONObject("""{"delivery_mode":"ASSESSMENT_ONLY"}""")))
        assertFalse(isAssessmentOnlyCourse(JSONObject("""{"course_type_code":"K12","delivery_mode":"LEARNING_CONTENT"}""")))
        assertFalse(isAssessmentOnlyCourse(JSONObject()))
    }

    @Test
    fun layoutPlacementIsDrivenByOrientationNotWidth() {
        assertFalse(StudentNavigationContract.usesLeftRail(false))
        assertTrue(StudentNavigationContract.usesLeftRail(true))
    }
}
