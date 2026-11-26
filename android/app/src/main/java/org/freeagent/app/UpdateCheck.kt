package org.freeagent.app

import org.json.JSONObject

/**
 * In-app update check (v0.12.1) — the pure half.
 *
 * Lille is sideloaded (no store), so without this the
 * user hears about a new version only if someone tells
 * them. The distribution branch carries a tiny
 * version.json next to the APK ({versionCode,
 * versionName}, refreshed by the build workflow at
 * publish time); the app compares it against the
 * installed build:
 *
 * - remote versionCode > installed → an update exists.
 * - Checks are throttled to once per 6 hours (the stamps
 *   live in the glue), however often the app opens.
 * - The notification fires ONCE per version, not once
 *   per check — a doorbell that rings daily for the same
 *   version is a doorbell people disable.
 * - The banner is dismissible per version; dismissing
 *   v17 says nothing about v18.
 *
 * A failed or malformed check is simply no update:
 * silence, never an error surface — the check is a
 * courtesy, not a dependency.
 */
object UpdateCheck {

    const val VERSION_URL =
        "https://raw.githubusercontent.com/Townsvilledh/free-agent/apk/version.json"

    /** The one-tap install link — the same URL every
     *  release ships to (stable signing line: it installs
     *  OVER the installed app, data intact). */
    const val APK_URL =
        "https://raw.githubusercontent.com/Townsvilledh/free-agent/apk/app-debug.apk"

    const val THROTTLE_MS = 6L * 60L * 60L * 1000L

    data class RemoteVersion(val versionCode: Int, val versionName: String)

    /** Parse a version.json body. Null on anything that
     *  isn't exactly the expected shape — a captive-portal
     *  HTML page or a truncated fetch must read as "no
     *  information", never as a version. */
    fun parse(body: String): RemoteVersion? {
        return try {
            val json = JSONObject(body)
            val code = json.optInt("versionCode", -1)
            val name = json.optString("versionName", "")
            if (code <= 0 || name.isBlank()) null
            else RemoteVersion(code, name)
        } catch (e: Exception) {
            null
        }
    }

    fun isUpdateAvailable(installedCode: Int, remote: RemoteVersion?): Boolean =
        remote != null && remote.versionCode > installedCode

    /** The 6-hour throttle: a check is due when the last
     *  one is at least THROTTLE_MS old. Never-checked
     *  (stamp 0) is always due. */
    fun shouldCheck(lastCheckMs: Long, nowMs: Long): Boolean =
        nowMs - lastCheckMs >= THROTTLE_MS

    /** Notify once per version: only when this remote
     *  version is newer than both the installed build AND
     *  the newest version already notified about. */
    fun shouldNotify(
        installedCode: Int,
        lastNotifiedCode: Int,
        remote: RemoteVersion?
    ): Boolean =
        isUpdateAvailable(installedCode, remote) &&
            remote!!.versionCode > lastNotifiedCode

    /** Show the banner when an update exists and the user
     *  hasn't dismissed THIS version. */
    fun shouldShowBanner(
        installedCode: Int,
        dismissedCode: Int,
        remote: RemoteVersion?
    ): Boolean =
        isUpdateAvailable(installedCode, remote) &&
            remote!!.versionCode > dismissedCode

    fun bannerText(remote: RemoteVersion): String =
        "Lille ${remote.versionName} is out — tap to update. " +
            "It installs over this version; your chats, tasks and " +
            "keys stay put."

    fun notificationText(remote: RemoteVersion): String =
        "Lille ${remote.versionName} is ready. Tap to download — " +
            "it installs over your current version."
}
