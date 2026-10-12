package com.quantaedge.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResourceOwnerKeyTest {
    @Test fun sameAccountGetsStablePrivateNamespace() {
        val auth = JSONObject().put("role", "STUDENT").put("studentId", 42)
        assertEquals(ResourceOwnerKey.fromAuth(auth), ResourceOwnerKey.fromAuth(JSONObject(auth.toString())))
    }
    @Test fun differentAccountsDoNotShareOfflineNamespace() {
        val first = JSONObject().put("role", "STUDENT").put("studentId", 42)
        val second = JSONObject().put("role", "STUDENT").put("studentId", 43)
        assertNotEquals(ResourceOwnerKey.fromAuth(first), ResourceOwnerKey.fromAuth(second))
    }
    @Test fun unauthenticatedAccountHasNoDownloadNamespace() {
        assertEquals("guest", ResourceOwnerKey.fromAuth(JSONObject()))
    }
}
