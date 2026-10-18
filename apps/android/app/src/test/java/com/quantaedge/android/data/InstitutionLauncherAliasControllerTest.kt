package com.quantaedge.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstitutionLauncherAliasControllerTest {
    @Test fun resolvesConfiguredSchoolWithoutUsingAnArbitraryInputAsASlot() {
        assertEquals(3, launcherSlotForInstitution("sch_001", "SCH_001=03;NAV-006=17"))
        assertEquals(17, launcherSlotForInstitution("NAV-006", "SCH_001=03;NAV-006=17"))
        assertNull(launcherSlotForInstitution("UNLISTED", "SCH_001=03;NAV-006=17"))
    }

    @Test fun rejectsInvalidCodesAndOutOfRangeSlots() {
        assertNull(launcherSlotForInstitution("school with spaces", "SCH001=04"))
        assertNull(launcherSlotForInstitution("SCH001", "SCH001=51"))
        assertNull(launcherSlotForInstitution("SCH001", "SCH001=0"))
    }

    @Test fun duplicateOrMalformedMappingsFailClosed() {
        assertNull(launcherSlotForInstitution("SCH001", "SCH001=01;SCH001=02"))
        assertNull(launcherSlotForInstitution("SCH001", "SCH001=01;NAV001=01"))
        assertNull(launcherSlotForInstitution("SCH001", "SCH001=01;malformed"))
        assertNull(launcherSlotForInstitution("SCH001", ""))
    }
}
