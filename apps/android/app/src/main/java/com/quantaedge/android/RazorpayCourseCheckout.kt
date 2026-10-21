package com.quantaedge.android

import org.json.JSONObject

/** Activity-owned Razorpay checkout bridge; payment entitlement is still verified by the API. */
data class CourseCheckoutResult(
    val paymentId: String? = null,
    val orderId: String? = null,
    val signature: String? = null,
    val userMessage: String? = null
)

interface RazorpayCourseCheckoutHost {
    fun launchCourseCheckout(order: JSONObject, onResult: (CourseCheckoutResult) -> Unit)
}
