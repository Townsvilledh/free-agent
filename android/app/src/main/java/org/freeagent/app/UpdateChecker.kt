package org.freeagent.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * In-app update check — the glue (v0.12.1). Owns the
 * stamps (last check, dismissed version, notified
 * version) in a small prefs file and the one fetch;
 * every decision is UpdateCheck's pure logic.
 *
 * Called from two places: MainActivity on open (banner
 * callback) and ProactiveWorker's periodic pass
 * (notification). The 6-hour throttle is stamped BEFORE
 * the fetch, so a dead network doesn't turn every app
 * open into a retry — the check is a courtesy with
 * manners. All failures are silent nulls.
 */
object UpdateChecker {

    private const val PREFS = "lille_update_check"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_DISMISSED = "dismissedCode"
    private const val KEY_NOTIFIED = "lastNotifiedCode"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dismissedCode(context: Context): Int =
        prefs(context).getInt(KEY_DISMISSED, 0)

    /** The banner's Later button: this version stops
     *  being bannered; the next one will show again. */
    fun dismiss(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_DISMISSED, versionCode).apply()
    }

    /**
     * One throttled check. [onResult] is always delivered
     * on the main thread with the available update, or
     * null (not due yet, no update, or the fetch failed —
     * indistinguishable by design). When [notify] is true
     * and a version is newly available, the
     * once-per-version notification is posted as well.
     */
    fun maybeCheck(
        context: Context,
        notify: Boolean,
        onResult: ((UpdateCheck.RemoteVersion?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val store = prefs(appContext)
        val now = System.currentTimeMillis()
        if (!UpdateCheck.shouldCheck(store.getLong(KEY_LAST_CHECK, 0L), now)) {
            onResult?.let { cb -> mainHandler.post { cb(null) } }
            return
        }
        store.edit().putLong(KEY_LAST_CHECK, now).apply()
        Thread {
            val remote = fetchRemote()
            val available = if (
                UpdateCheck.isUpdateAvailable(BuildConfig.VERSION_CODE, remote)
            ) {
                remote
            } else {
                null
            }
            if (available != null && notify) {
                val lastNotified = store.getInt(KEY_NOTIFIED, 0)
                if (UpdateCheck.shouldNotify(
                        BuildConfig.VERSION_CODE, lastNotified, available
                    )
                ) {
                    UpdateNotify.notifyUpdate(appContext, available)
                    store.edit()
                        .putInt(KEY_NOTIFIED, available.versionCode)
                        .apply()
                }
            }
            if (onResult != null) {
                mainHandler.post { onResult(available) }
            }
        }.start()
    }

    private fun fetchRemote(): UpdateCheck.RemoteVersion? {
        return try {
            val request = Request.Builder().url(UpdateCheck.VERSION_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    null
                } else {
                    UpdateCheck.parse(response.body?.string() ?: "")
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
