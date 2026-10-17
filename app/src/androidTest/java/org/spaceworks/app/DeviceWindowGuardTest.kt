package org.spaceworks.app

import org.junit.Assert.*
import org.junit.Test

class DeviceWindowGuardTest {
    @Test fun onlyConfirmedLauncherANRCanBeRecovered() {
        assertEquals("com.google.android.apps.nexuslauncher", DeviceWindowPolicy.recoverableLauncher(
            listOf("Pixel Launcher isn't responding"), setOf("com.google.android.apps.nexuslauncher"), "Space Works"))
        assertTrue(DeviceWindowPolicy.isFailureTitle("Pixel Launcher isn’t responding"))
    }
    @Test fun appANRAndUnknownDialogsMustFail() {
        assertNull(DeviceWindowPolicy.recoverableLauncher(listOf("Space Works isn't responding"), setOf("org.spaceworks.app"), "Space Works"))
        assertNull(DeviceWindowPolicy.recoverableLauncher(listOf("Pixel Launcher isn't responding"), setOf("unknown.package"), "Space Works"))
        assertNull(DeviceWindowPolicy.recoverableLauncher(listOf("Pixel Launcher keeps stopping"), setOf("com.google.android.apps.nexuslauncher"), "Space Works"))
    }
    @Test fun titleAloneCannotAuthorizeClosingAnAppAndBrandNameIsProtected() {
        assertNull(DeviceWindowPolicy.recoverableLauncher(listOf("Pixel Launcher isn't responding"), emptySet(), "Space Works"))
        assertNull(DeviceWindowPolicy.recoverableLauncher(listOf("Pixel Launcher isn't responding"), setOf("com.google.android.apps.nexuslauncher"), "Pixel Launcher"))
        assertEquals(setOf("com.google.android.apps.nexuslauncher"), DeviceWindowPolicy.errorPackages("Window{1 u0 Application Error: com.google.android.apps.nexuslauncher}"))
        assertEquals(setOf("com.google.android.apps.nexuslauncher"), DeviceWindowPolicy.errorPackages("mCurrentFocus=Window{1 u0 Application Not Responding: com.google.android.apps.nexuslauncher}"))
    }
}
