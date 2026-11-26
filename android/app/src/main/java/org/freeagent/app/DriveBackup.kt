package org.freeagent.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Layer 2 of the backup (v0.11, BACKUP.md): the sealed blob,
 * synced to the USER'S OWN Google Drive appDataFolder — a
 * hidden per-app folder Google shows to no other app and
 * never lists in the user's Drive UI. Google stores the
 * lock-box, never the key: the blob is BackupCrypto output,
 * sealed before it leaves the phone.
 *
 * This is the full Drive REST path (find / download / create /
 * update) over plain HTTPS with the OAuth access token the
 * Backup screen obtains after the user's Google consent. It
 * is gated by BuildConfig.DRIVE_ENABLED, which stays FALSE
 * until the Google Cloud OAuth client for this app exists
 * (package + the stable signing certificate's fingerprint,
 * registered in Ethuram's own Cloud project — BACKUP.md lists
 * the exact console steps). With the flag off, every entry
 * point reports Disabled and no Google sign-in is ever
 * attempted.
 *
 * All calls are blocking — callers run them off the UI thread.
 */
object DriveBackup {

    const val FILE_NAME = "lille-backup.lillebak"
    const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        object Disabled : Result<Nothing>()
        data class Failed(val message: String) : Result<Nothing>()
    }

    fun isConfigured(): Boolean = BuildConfig.DRIVE_ENABLED

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    /** The id of the newest backup file in appDataFolder, or
     *  null when none exists yet. */
    fun findBackupFileId(token: String): Result<String?> {
        if (!isConfigured()) return Result.Disabled
        return try {
            val q = java.net.URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
            val url = "$FILES_URL?spaces=appDataFolder&q=$q" +
                "&fields=files(id,modifiedTime)&orderBy=modifiedTime%20desc"
            client.newCall(authed(url, token).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.Failed("Drive list failed (HTTP ${resp.code}).")
                }
                val body = resp.body?.string() ?: return Result.Failed("Drive sent no answer.")
                val files = JSONObject(body).optJSONArray("files")
                if (files == null || files.length() == 0) Result.Ok(null)
                else Result.Ok(files.getJSONObject(0).getString("id"))
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Drive unreachable.")
        }
    }

    fun download(token: String, fileId: String): Result<ByteArray> {
        if (!isConfigured()) return Result.Disabled
        return try {
            client.newCall(authed("$FILES_URL/$fileId?alt=media", token).get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return Result.Failed("Drive download failed (HTTP ${resp.code}).")
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null) Result.Failed("Drive sent an empty file.")
                    else Result.Ok(bytes)
                }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Drive unreachable.")
        }
    }

    /** Create the backup file, or overwrite the existing one.
     *  Returns the file id. */
    fun upload(token: String, blob: ByteArray, existingId: String?): Result<String> {
        if (!isConfigured()) return Result.Disabled
        return try {
            if (existingId != null) {
                val req = authed("$UPLOAD_URL/$existingId?uploadType=media", token)
                    .patch(blob.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return Result.Failed("Drive upload failed (HTTP ${resp.code}).")
                    }
                    Result.Ok(existingId)
                }
            } else {
                val metadata = JSONObject()
                    .put("name", FILE_NAME)
                    .put("parents", org.json.JSONArray().put("appDataFolder"))
                    .toString()
                val body = MultipartBody.Builder()
                    .setType("multipart/related")
                    .addPart(
                        MultipartBody.Part.createFormData(
                            "metadata", null,
                            metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
                        )
                    )
                    .addPart(
                        MultipartBody.Part.createFormData(
                            "file", FILE_NAME,
                            blob.toRequestBody("application/octet-stream".toMediaType())
                        )
                    )
                    .build()
                val req = authed("$UPLOAD_URL?uploadType=multipart", token).post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return Result.Failed("Drive upload failed (HTTP ${resp.code}).")
                    }
                    val id = JSONObject(resp.body?.string() ?: "{}").optString("id", "")
                    if (id.isEmpty()) Result.Failed("Drive did not confirm the upload.")
                    else Result.Ok(id)
                }
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Drive unreachable.")
        }
    }
}
