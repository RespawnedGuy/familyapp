package io.github.respawnedguy.familyapp

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun versionLabelShowsVersion() {
        assertEquals("Version 0.1.7", AppInfo.versionLabel("0.1.7"))
    }

    @Test
    fun versionLabelHandlesMissingVersion() {
        assertEquals("Version unbekannt", AppInfo.versionLabel(""))
    }
}
