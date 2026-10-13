package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentNavigationContractTest {
    @Test
    fun canonicalGroupsMatchTheWebOrder() {
        assertEquals(listOf("today", "learn", "practice", "progress", "library"), StudentNavigationContract.studentGroupIds)
        assertEquals(listOf("family", "children", "profile"), StudentNavigationContract.parentGroupIds)
    }

    @Test
    fun todayOpensStudentHomeAndLessonAndClassRoutesBelongToLearn() {
        assertEquals("today", StudentNavigationContract.studentGroupForRoute(AppRoute.STUDENT_HOME, "learn"))
        listOf(AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.TUTOR, AppRoute.CLASSES, AppRoute.RECORDING_PLAYER)
            .forEach { route -> assertEquals("learn", StudentNavigationContract.studentGroupForRoute(route, "today")) }
    }

    @Test
    fun practiceTestsResultsAndLeaderboardStayTogether() {
        listOf(
            AppRoute.PRACTICE, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT,
            AppRoute.ASSESSMENT_RESULTS, AppRoute.ASSESSMENT_REVIEW, AppRoute.LEADERBOARD
        ).forEach { route -> assertEquals("practice", StudentNavigationContract.studentGroupForRoute(route, "today")) }
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
    fun parentRoutesRemainRoleSpecific() {
        assertEquals("family", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_HOME, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_CHILD_CREATE, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_CHILD_MANAGE, "family"))
        assertEquals("profile", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_PROFILE, "family"))
        assertEquals("children", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_REPORT, "children"))
        assertEquals("family", StudentNavigationContract.parentGroupForRoute(AppRoute.PARENT_REPORT, "invalid"))
    }

    @Test
    fun layoutPlacementIsDrivenByOrientationNotWidth() {
        assertFalse(StudentNavigationContract.usesLeftRail(false))
        assertTrue(StudentNavigationContract.usesLeftRail(true))
    }
}
