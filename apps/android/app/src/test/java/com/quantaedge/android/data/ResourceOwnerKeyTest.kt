package com.quantaedge.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResourceOwnerKeyTest {
    @Test fun sameAccountGetsStablePrivateNamespace() {
        assertEquals(
            ResourceOwnerKey.fromIdentity("STUDENT", "42"),
            ResourceOwnerKey.fromIdentity("student", "42")
        )
    }

    @Test fun differentAccountsDoNotShareOfflineNamespace() {
        assertNotEquals(
            ResourceOwnerKey.fromIdentity("STUDENT", "42"),
            ResourceOwnerKey.fromIdentity("STUDENT", "43")
        )
    }

    @Test fun unauthenticatedAccountHasNoDownloadNamespace() {
        assertEquals("guest", ResourceOwnerKey.fromIdentity("", ""))
        assertEquals("guest", ResourceOwnerKey.fromIdentity("STUDENT", "0"))
    }
}
