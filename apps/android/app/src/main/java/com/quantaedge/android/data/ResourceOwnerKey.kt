package com.quantaedge.android.data

import org.json.JSONObject
import java.security.MessageDigest

/** Derives a non-identifying namespace for the authenticated account offline resources. */
object ResourceOwnerKey {
    fun fromAuth(auth: JSONObject): String {
        val role = auth.optString("role", "").uppercase()
        val identifier = when {
            auth.has("studentId") && !auth.isNull("studentId") -> auth.optString("studentId")
            auth.has("userId") && !auth.isNull("userId") -> auth.optString("userId")
            else -> ""
        }
        if (role.isBlank() || identifier.isBlank() || identifier == "0") return "guest"
        return MessageDigest.getInstance("SHA-256")
            .digest((role + ":" + identifier).toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
    }
}
