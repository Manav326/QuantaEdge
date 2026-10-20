package com.quantaedge.android.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.quantaedge.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Optional-config-safe FCM registration scoped to the authenticated institution/account. */
object NotificationPushRegistration {
    const val CHANNEL_ID = "quantaedge_notifications"
    const val OPEN_ACTION = "com.quantaedge.android.OPEN_NOTIFICATION"
    private const val PREFS = "qe_notification_push"
    private const val DEVICE_ID_KEY = "device_id"
    private const val PERMISSION_REQUESTED_KEY = "permission_requested"
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "QuantaEdge notifications", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Course updates, learning reminders and important account notifications"
            enableVibration(true)
        })
    }

    fun permissionWasRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PERMISSION_REQUESTED_KEY, false)

    fun setPermissionWasRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PERMISSION_REQUESTED_KEY, true).apply()
    }

    fun isFirebaseConfigured(): Boolean = BuildConfig.FCM_CLIENT_CONFIGURED

    private fun configuredFirebaseApp(context: Context): FirebaseApp? {
        if (!BuildConfig.FCM_CLIENT_CONFIGURED) return null
        val appContext = context.applicationContext
        FirebaseApp.getApps(appContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.let { return it }
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
        return runCatching { FirebaseApp.initializeApp(appContext, options) }.getOrNull()
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun sync(context: Context) {
        val app = configuredFirebaseApp(context) ?: return
        if (!canPostNotifications(context)) return
        val token = runCatching { FirebaseMessaging.getInstance(app).token.await() }.getOrNull()?.trim().orEmpty()
        if (token.isBlank()) return
        registerAuthenticatedDevice(context.applicationContext, token)
    }

    fun onNewToken(context: Context, token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return
        if (!isFirebaseConfigured() || !canPostNotifications(context)) return
        backgroundScope.launch {
            runCatching { registerAuthenticatedDevice(context.applicationContext, cleanToken) }
        }
    }

    suspend fun revokeCurrent(context: Context) {
        val appContext = context.applicationContext
        ApiClient.initialize(appContext)
        setActiveTenantFromPreferences(appContext)
        ApiClient.postObject("/api/v1/notifications/devices/revoke", JSONObject().put("deviceId", installationId(appContext)))
    }

    private suspend fun registerAuthenticatedDevice(context: Context, token: String) {
        ApiClient.initialize(context.applicationContext)
        setActiveTenantFromPreferences(context.applicationContext)
        val payload = JSONObject()
            .put("deviceId", installationId(context))
            .put("token", token)
            .put("platform", "ANDROID")
            .put("packageName", context.packageName)
            .put("appVersion", BuildConfig.VERSION_NAME)
        ApiClient.postObject("/api/v1/notifications/devices", payload)
    }

    private fun setActiveTenantFromPreferences(context: Context) {
        ApiClient.setInstitutionCode(context.getSharedPreferences("qe_preferences", Context.MODE_PRIVATE)
            .getString("institutionCode", null))
    }

    private fun installationId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(DEVICE_ID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID_KEY, generated).apply()
        return generated
    }

}
