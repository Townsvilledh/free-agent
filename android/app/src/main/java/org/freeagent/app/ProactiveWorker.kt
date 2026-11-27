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
 * debrief in the main chat. The other lane is the scheduled
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
            now = { System.currentTimeMillis() },
            heartbeat = store
        )
        val summary = withContext(Dispatchers.IO) { pass.run() }
        for (taskId in summary.pickupTaskIds) {
            TaskWorker.enqueue(applicationContext, taskId)
        }
        runDueScheduledJobs()
        // v0.12.1: the periodic pass also carries the
        // in-app update check — UpdateChecker's own 6-hour
        // throttle + once-per-version notification gate
        // keep this pass (15-min cadence, Doze permitting)
        // from ever becoming noise.
        UpdateChecker.maybeCheck(applicationContext, notify = true)
        return Result.success()
    }

    /** THE CRON LANE (v1.0.4, steal #6): scheduled jobs run
     *  ISOLATED from the heartbeat — each due job becomes
     *  its own light task record carrying only its own
     *  instruction + its recipe's tool restrictions, and
     *  the job's next run is settled by ScheduledJobs'
     *  arithmetic (success reschedules; failure backs off,
     *  capped; repeated failure surfaces in the debrief
     *  through the store's reports). Jobs live in the
     *  agent-state store's jobs section (they ride the
     *  backup); a job names a recipe from the library. */
    private fun runDueScheduledJobs() {
        val stateStore = AgentStateStore(applicationContext)
        val jobsText = stateStore.readSection(
            AgentStateStore.SECTION_JOBS
        ) ?: return
        val jobsArr = try {
            org.json.JSONArray(jobsText)
        } catch (e: Exception) {
            return
        }
        val jobs = MutableList(jobsArr.length()) {
            jobsArr.getJSONObject(it)
        }
        val nowMs = System.currentTimeMillis()
        val due = ScheduledJobs.dueJobs(jobs, nowMs)
        if (due.isEmpty()) return
        val libraryText = stateStore.readSection(
            AgentStateStore.SECTION_RECIPES
        )
        val library = try {
            if (libraryText != null) org.json.JSONObject(libraryText)
            else org.json.JSONObject()
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val taskStore = TaskStore(applicationContext)
        for (job in due) {
            val recipeName = job.optString("recipeName")
            val recipeRaw = library.optString(recipeName)
            val recipe = try {
                if (recipeRaw.isEmpty()) null
                else org.json.JSONObject(recipeRaw)
            } catch (e: Exception) {
                null
            }
            if (recipe == null) {
                settleJob(stateStore, jobs, job, false,
                    "recipe \"$recipeName\" is not in the library")
                continue
            }
            val values = mutableMapOf<String, String>()
            val params = job.optJSONObject("params")
            if (params != null) {
                val keys = params.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    values[k] = params.optString(k)
                }
            }
            val instruction = Recipes.substitute(recipe, values)
            val task = taskStore.create(instruction)
            val taskId = task.optString("id")
            taskStore.update(taskId) {
                it.put("kind", "recipe")
                it.put("recipeName", recipeName)
                it.put("recipeJson", recipe.toString())
                it.put("scheduledJobId", job.optString("id"))
            }
            TaskWorker.enqueue(applicationContext, taskId)
            settleJob(stateStore, jobs, job, true, "")
        }
    }

    /** Settle one job after its run was dispatched: the
     *  pure arithmetic decides the next run (or the end of
     *  a one-shot), and a failure that keeps failing joins
     *  the debrief instead of dying quietly. */
    private fun settleJob(
        stateStore: AgentStateStore,
        jobs: MutableList<org.json.JSONObject>,
        job: org.json.JSONObject,
        ok: Boolean,
        error: String
    ) {
        val nowMs = System.currentTimeMillis()
        val settled = ScheduledJobs.afterRun(job, ok, error, nowMs)
        for (i in jobs.indices) {
            if (jobs[i].optString("id") == settled.optString("id")) {
                jobs[i] = settled
                break
            }
        }
        val arr = org.json.JSONArray()
        jobs.forEach { arr.put(it) }
        stateStore.writeSection(
            AgentStateStore.SECTION_JOBS, arr.toString()
        )
        if (!ok && ScheduledJobs.failureNeedsSurfacing(settled)) {
            val store = ProactiveStore(applicationContext)
            val reports = store.reports()
            reports.add(
                org.json.JSONObject()
                    .put(
                        "id",
                        "rpt-" + java.util.UUID.randomUUID()
                            .toString().substring(0, 8)
                    )
                    .put("status", "BLOCKED")
                    .put(
                        "title",
                        "A scheduled job keeps failing: " +
                            settled.optString("name")
                    )
                    .put(
                        "body",
                        "The scheduled job \"" +
                            settled.optString("name") + "\" has " +
                            "failed ${settled.optInt("failCount")} " +
                            "times in a row. Last problem: " +
                            settled.optString("lastError")
                    )
                    .put("at", nowMs)
            )
            store.saveReports(reports)
        }
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
