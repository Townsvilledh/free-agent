package org.freeagent.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The on-device proactive lane (v0.7): a periodic WorkManager job
 * (15 min is WorkManager's minimum period; Doze may defer it —
 * see PROACTIVITY.md for the honest limits) that runs one
 * ProactivePass over the local store: due URL watches are checked,
 * queued tasks are picked up (enqueued with TaskWorker), and
 * finished pickups are settled into the "while you were away"
 * report list on the Tasks screen. The other lane is the scheduled
 * GitHub Actions pass (lille-proactive.yml) for non-private state.
 */
class ProactiveWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = ProactiveStore(applicationContext)
        val taskStore = TaskStore(applicationContext)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val fetch: (String) -> Pair<Int, String> = { url ->
            val resp = client.newCall(Request.Builder().url(url).build())
                .execute()
            val body = resp.body?.string()?.take(512_000) ?: ""
            resp.code to body
        }
        val pass = ProactivePass(
            data = store,
            tasks = { taskStore.all() },
            fetch = fetch,
            now = { System.currentTimeMillis() }
        )
        val summary = withContext(Dispatchers.IO) { pass.run() }
        for (taskId in summary.pickupTaskIds) {
            TaskWorker.enqueue(applicationContext, taskId)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "lille-proactive-pass"

        /** Idempotent: safe to call on every app start / boot. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
