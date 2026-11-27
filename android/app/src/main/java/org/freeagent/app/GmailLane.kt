package org.freeagent.app

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * On-device Gmail (v1.0.4) — the connection + the gateway.
 *
 * The connection reuses the app's OWN Google OAuth stack
 * (the Google Sign-In + GoogleAuthUtil machinery the Drive
 * backup already runs on) with a SEPARATE consent: backup
 * asks for drive.appdata; Gmail asks for gmail.readonly,
 * and for gmail.send only when the user explicitly turns
 * sending on. The account + grant facts live in plain
 * prefs (they are not secrets); the access token is minted
 * per call by GoogleAuthUtil and never stored, never
 * logged, never anywhere near model context.
 *
 * The gateway speaks the Gmail API over HTTPS and returns
 * ONLY the pure layer's shapes (DeviceMail): heads and
 * read-observations that GmailFormat has already redacted.
 * Sending is gated in the RUNNER by MailSendGate's triple
 * gate; this gateway additionally refuses when the
 * Settings send flag is off (defence in depth — no path
 * enables a send silently, mirroring the core's law).
 */
object GmailConnection {

    const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"

    private const val PREFS = "lille_gmail"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_SEND_SCOPE = "sendScope"
    private const val KEY_SEND_ENABLED = "sendEnabled"

    fun isConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONNECTED, false) &&
            !prefs.getString(KEY_ACCOUNT, "").isNullOrEmpty()
    }

    fun account(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT, "") ?: ""

    /** Sending's Settings flag — gate 1 of the triple
     *  gate. Default OFF, always. */
    fun sendEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_ENABLED, false)

    fun setSendEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEND_ENABLED, enabled).apply()
    }

    /** Gate 2's fact: did the granted consent include the
     *  send scope? Recorded at sign-in from the account's
     *  own granted scopes. */
    fun sendScopeGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_SCOPE, false)

    /** The sign-in options for the Gmail consent. Sending
     *  scope is requested only when [withSend] — the user
     *  asking to enable sending is the only door to it. */
    fun signInOptions(withSend: Boolean): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_READONLY))
        if (withSend) builder.requestScopes(Scope(SCOPE_SEND))
        return builder.build()
    }

    fun onSignedIn(context: Context, account: GoogleSignInAccount) {
        val granted = account.grantedScopes.map { it.scopeUri }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT, account.email ?: "")
            .putBoolean(KEY_CONNECTED, true)
            .putBoolean(KEY_SEND_SCOPE, granted.any { it == SCOPE_SEND })
            .apply()
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** A short-lived access token for the connected
     *  account, minted by the platform. Blocking — call
     *  from a worker thread. Null when not connected or
     *  the mint fails (the gateway turns that into the
     *  honest error, never a fake read). */
    fun token(context: Context): String? {
        val acct = account(context)
        if (acct.isEmpty()) return null
        val scopes = StringBuilder("oauth2:").append(SCOPE_READONLY)
        if (sendScopeGranted(context)) scopes.append(" ").append(SCOPE_SEND)
        return try {
            GoogleAuthUtil.getToken(
                context.applicationContext, acct, scopes.toString()
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** The Gmail API gateway (device lane). */
class GmailGateway(context: Context) : MailGateway {

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun authed(path: String): Request.Builder {
        val token = GmailConnection.token(appContext)
            ?: throw IOException("gmail token unavailable — reconnect in Settings")
        return Request.Builder()
            .url("https://gmail.googleapis.com/gmail/v1/users/me/$path")
            .header("Authorization", "Bearer $token")
    }

    private fun getJson(path: String): JSONObject {
        client.newCall(authed(path).get().build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code != 200) {
                throw IOException("gmail answered HTTP ${resp.code}")
            }
            return JSONObject(body)
        }
    }

    private fun messageIds(query: String?, max: Int): List<String> {
        val path = StringBuilder("messages?maxResults=")
            .append(max.coerceIn(1, 25))
        if (!query.isNullOrBlank()) {
            path.append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val list = getJson(path.toString())
        val arr = list.optJSONArray("messages") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it).optString("id") }
            .filter { it.isNotEmpty() }
    }

    private fun heads(query: String?, max: Int): List<MailHead> =
        messageIds(query, max).map { id ->
            GmailFormat.parseHead(getJson("messages/$id?format=metadata"))
        }

    override fun list(max: Int): List<MailHead> = heads(null, max)

    override fun search(query: String, max: Int): List<MailHead> =
        heads(query, max)

    override fun read(id: String): String {
        if (id.isBlank()) return "error: gmail_read needs a message id"
        return try {
            GmailFormat.formatRead(getJson("messages/$id?format=full"))
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    override fun send(to: String, subject: String, body: String): String {
        // Defence in depth: the runner enforces the full
        // triple gate (MailSendGate); the gateway itself
        // also refuses while the Settings flag is off, so
        // no caller bug can turn into a silent send.
        if (!GmailConnection.sendEnabled(appContext)) {
            return "error: SENTINEL — sending is off (Settings → " +
                "Gmail). The message was NOT sent."
        }
        return try {
            val raw = "To: $to\r\nSubject: $subject\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n$body"
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toByteArray(Charsets.UTF_8))
            val payload = JSONObject().put("raw", encoded)
            client.newCall(
                authed("messages/send").post(
                    payload.toString()
                        .toRequestBody("application/json".toMediaType())
                ).build()
            ).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.code != 200) {
                    "error: gmail send answered HTTP ${resp.code}"
                } else {
                    val id = JSONObject(respBody).optString("id")
                    "GMAIL_SENT id=$id to=$to — sent and confirmed by Gmail."
                }
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
