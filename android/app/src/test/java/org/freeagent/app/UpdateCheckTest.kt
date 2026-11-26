package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateCheck (v0.12.1): the in-app update check's pure
 * logic — parse, compare, throttle, and the
 * once-per-version gates for the notification and the
 * banner. A sideloaded app has no store to announce new
 * versions; these pins keep the announcement honest
 * (only a strictly newer versionCode counts) and quiet
 * (throttled checks, notify once per version, dismiss
 * per version).
 */
class UpdateCheckTest {

    private val v17 = UpdateCheck.RemoteVersion(17, "0.12.1")
    private val v18 = UpdateCheck.RemoteVersion(18, "0.13.0")

    // ---- parsing ----

    @Test
    fun parsesThePublishedShape() {
        val parsed = UpdateCheck.parse(
            """{"versionCode": 17, "versionName": "0.12.1"}"""
        )
        assertEquals(v17, parsed)
    }

    @Test
    fun malformedBodiesAreNoInformationNotVersions() {
        assertNull(UpdateCheck.parse(""))
        assertNull(UpdateCheck.parse("<html>captive portal</html>"))
        assertNull(UpdateCheck.parse("""{"versionCode": "17"}"""))
        assertNull(UpdateCheck.parse("""{"versionName": "0.12.1"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode": 0, "versionName": "0"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode": 17, "versionName": " "}"""))
    }

    // ---- comparison ----

    @Test
    fun onlyAStrictlyNewerCodeIsAnUpdate() {
        assertTrue(UpdateCheck.isUpdateAvailable(16, v17))
        assertFalse(UpdateCheck.isUpdateAvailable(17, v17))
        assertFalse(UpdateCheck.isUpdateAvailable(18, v17))
        assertFalse(UpdateCheck.isUpdateAvailable(16, null))
    }

    // ---- throttle ----

    @Test
    fun checksAreThrottledToSixHours() {
        val t0 = 1_000_000_000L
        assertTrue(UpdateCheck.shouldCheck(0L, t0)) // never checked
        assertFalse(UpdateCheck.shouldCheck(t0, t0 + UpdateCheck.THROTTLE_MS - 1))
        assertTrue(UpdateCheck.shouldCheck(t0, t0 + UpdateCheck.THROTTLE_MS))
        assertTrue(UpdateCheck.shouldCheck(t0, t0 + 24L * 3600L * 1000L))
    }

    // ---- once-per-version gates ----

    @Test
    fun notificationFiresOncePerVersion() {
        // Installed 16, never notified: v17 notifies.
        assertTrue(UpdateCheck.shouldNotify(16, 0, v17))
        // Already notified about v17: silent.
        assertFalse(UpdateCheck.shouldNotify(16, 17, v17))
        // A newer version notifies again.
        assertTrue(UpdateCheck.shouldNotify(16, 17, v18))
        // Nothing to notify when there's no update.
        assertFalse(UpdateCheck.shouldNotify(17, 0, v17))
        assertFalse(UpdateCheck.shouldNotify(16, 0, null))
    }

    @Test
    fun bannerDismissalIsPerVersion() {
        assertTrue(UpdateCheck.shouldShowBanner(16, 0, v17))
        // Dismissed v17: hidden for v17…
        assertFalse(UpdateCheck.shouldShowBanner(16, 17, v17))
        // …but v18 shows.
        assertTrue(UpdateCheck.shouldShowBanner(16, 17, v18))
        // No banner when installed is current.
        assertFalse(UpdateCheck.shouldShowBanner(17, 0, v17))
    }

    // ---- copy ----

    @Test
    fun bannerNamesTheVersionAndPromisesInPlaceInstall() {
        val text = UpdateCheck.bannerText(v17)
        assertTrue(text.contains("0.12.1"))
        assertTrue(text.contains("installs over this version"))
    }

    @Test
    fun distributionUrlsPointAtTheApkBranch() {
        assertTrue(UpdateCheck.VERSION_URL.endsWith("/apk/version.json"))
        assertTrue(UpdateCheck.APK_URL.endsWith("/apk/app-debug.apk"))
    }
}
