package com.quantaedge.android.ui

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredLessonVisualGateTest {
    @Test
    fun acceptsExplicitlyApprovedBilingualDiagram() {
        val block = JSONObject(
            """{"type":"diagram","student_renderable":true,"review_status":"APPROVED_FOR_STUDENT_RENDERING","alt_by_locale":{"en":"A three-step water cycle diagram.","hi":"जल चक्र का तीन चरणों वाला आरेख।"},"diagram_data":{"nodes":[{"id":"a","label_by_locale":{"en":"Evaporation","hi":"वाष्पीकरण"}}],"edges":[]}}"""
        )
        assertTrue(isApprovedDiagram(block))
    }

    @Test
    fun rejectsUnreviewedOrIncompleteDiagrams() {
        val pending = JSONObject(
            """{"type":"diagram","student_renderable":true,"review_status":"PENDING_REVIEW","alt_by_locale":{"en":"Diagram","hi":"आरेख"},"diagram_data":{"nodes":[{"id":"a"}]}}"""
        )
        val missingHindiAlt = JSONObject(
            """{"type":"diagram","student_renderable":true,"review_status":"APPROVED","alt_by_locale":{"en":"Diagram"},"diagram_data":{"nodes":[{"id":"a"}]}}"""
        )
        val emptyNodes = JSONObject(
            """{"type":"diagram","student_renderable":true,"review_status":"APPROVED","alt_by_locale":{"en":"Diagram","hi":"आरेख"},"diagram_data":{"nodes":[]}}"""
        )
        val nonRenderable = JSONObject(
            """{"type":"diagram","student_renderable":false,"review_status":"APPROVED","alt_by_locale":{"en":"Diagram","hi":"आरेख"},"diagram_data":{"nodes":[{"id":"a"}]}}"""
        )
        assertFalse(isApprovedDiagram(pending))
        assertFalse(isApprovedDiagram(missingHindiAlt))
        assertFalse(isApprovedDiagram(emptyNodes))
        assertFalse(isApprovedDiagram(nonRenderable))
    }
}
