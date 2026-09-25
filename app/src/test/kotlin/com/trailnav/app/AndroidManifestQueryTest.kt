package com.trailnav.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidManifestQueryTest {
    @Test
    fun manifestDeclaresOnlyTheThreeFallbackVisibilityShapes() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:scheme=\"content\""))
        assertTrue(manifest.contains("android:scheme=\"file\""))
        assertTrue(manifest.contains("android:scheme=\"file\" android:mimeType=\"*/*\""))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }
}
