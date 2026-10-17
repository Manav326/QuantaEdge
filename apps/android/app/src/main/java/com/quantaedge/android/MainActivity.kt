package com.quantaedge.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.ui.QuantaEdgeApp
import com.quantaedge.android.ui.QuantaEdgeTheme
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

class MainActivity : ComponentActivity(), PaymentResultWithDataListener, RazorpayCourseCheckoutHost {
    private var pendingCourseOrderId: String? = null
    private var pendingCourseResult: ((CourseCheckoutResult) -> Unit)? = null

    override fun launchCourseCheckout(order: JSONObject, onResult: (CourseCheckoutResult) -> Unit) {
        val orderId = order.optString("orderId").trim()
        val localOrderId = order.optLong("localOrderId", 0L)
        val keyId = order.optString("keyId").trim()
        val amount = order.optLong("amount", 0L)
        val currency = order.optString("currency", "INR").trim().uppercase()
        if (!orderId.matches(Regex("order_[A-Za-z0-9]+")) || localOrderId <= 0L ||
            keyId.isBlank() || amount <= 0L || currency != "INR") {
            onResult(CourseCheckoutResult(userMessage = "Checkout could not be started. Please try again."))
            return
        }
        try {
            val checkout = Checkout().apply { setKeyID(keyId) }
            val options = JSONObject().apply {
                put("key", keyId)
                put("order_id", orderId)
                put("currency", currency)
                put("amount", amount)
                put("name", "QuantaEdge")
                put("description", order.optString("description", "Course access"))
                put("theme", JSONObject().put("color", "#E87524"))
            }
            pendingCourseOrderId = orderId
            pendingCourseResult = onResult
            Checkout.preload(applicationContext)
            checkout.open(this, options)
        } catch (_: Exception) {
            pendingCourseOrderId = null
            pendingCourseResult = null
            onResult(CourseCheckoutResult(userMessage = "The payment window could not be opened. Please retry."))
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val expectedOrderId = pendingCourseOrderId
        val callback = pendingCourseResult
        pendingCourseOrderId = null
        pendingCourseResult = null
        if (callback == null) return
        val returnedOrderId = paymentData?.orderId.orEmpty()
        if (expectedOrderId.isNullOrBlank() || returnedOrderId != expectedOrderId) {
            callback(CourseCheckoutResult(userMessage = "The payment confirmation did not match this course order. Please contact support if money was deducted."))
            return
        }
        callback(CourseCheckoutResult(
            paymentId = razorpayPaymentId,
            orderId = returnedOrderId,
            signature = paymentData?.signature
        ))
    }

    override fun onPaymentError(code: Int, description: String?, paymentData: PaymentData?) {
        val callback = pendingCourseResult
        pendingCourseOrderId = null
        pendingCourseResult = null
        callback?.invoke(CourseCheckoutResult(userMessage = "Payment was not completed. You can try again."))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        ApiClient.initialize(applicationContext)
        setContent { QuantaEdgeTheme { QuantaEdgeApp() } }
    }
}
