package org.freeagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager front-end for durable tasks (v0.4).
 *
 * - The worker runs as a FOREGROUND worker (persistent notification)
 *   while a task is actively running, so the user can see Lille is
 *   working and Android treats the work as user-visible.
 * - A CONNECTED network constraint means a task dispatched while
 *   offline simply waits in WorkManager's persistent queue — it is
 *   not lost, and it starts by itself when the network returns.
 * - Waiting states (network/route) schedule their own next run
 *   at the record's nextRetryAt (v1.0.2, NO SLEEPING WHILE A
 *   ROUTE LIVES): the runner's rest is the shortest per-route
 *   cooldown, and WorkManager's escalating retry backoff would
 *   only ever make that rest LONGER, so the worker returns
 *   success and enqueues a delayed request for exactly the
 *   record's time — re-evaluated on every run, because every
 *   run re-schedules from the fresh record. The checkpoint in
 *   the TaskStore means the retry RESUMES the task instead of
 *   restarting it.
 * - WorkManager's own queue survives reboot; BootReceiver also
 *   re-queues any task left in a resumable state.
 */
class TaskWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        // IDENTITY RE-CHECK mode (v1.0.9): a read-only
        // pass — the record's sign-in read runs to a
        // conclusion (or an explicit unreadable) WITHOUT
        // running the task: no state transitions, no
        // un-parking, no reporter. It takes the browser
        // lane's gate like any browser work; contention
        // retries later, and the request stamp on the
        // record survives until a read lands.
        if (inputData.getString(KEY_MODE) == MODE_IDENTITY_RECHECK) {
            TaskStore(applicationContext).get(taskId)
                ?: return Result.failure()
            watchdogPass(applicationContext, excluding = taskId)
            if (!TaskGate.shared.tryAcquire(taskId, true)) {
                return Result.retry()
            }
            try {
                TaskRunner(applicationContext)
                    .runIdentityRecheck(taskId)
            } finally {
                TaskGate.shared.release(taskId)
            }
            return Result.success()
        }
        // Bounded concurrency (v1.0.1, TaskGate): at most
        // two task runs progress at once, and the exclusive
        // browser lane admits one browser run at a time. A
        // denied run retries later WITHOUT touching the
        // record — the task's state and checkpoint are
        // exactly as they were, so serialisation on the
        // lane never fails either task.
        val record = TaskStore(applicationContext).get(taskId)
            ?: return Result.failure()
        // Watchdog (v1.0.2): every worker pass also sweeps
        // the OTHER tasks — an expired wait gets its pass, a
        // stale wait gets a forced retry. This task's own
        // run below is its pass, so it is excluded here.
        watchdogPass(applicationContext, excluding = taskId)
        if (!TaskGate.shared.tryAcquire(
                taskId, TaskRunner.isBrowserTask(record)
            )
        ) {
            return Result.retry()
        }
        try {
            return doWorkAdmitted(taskId)
        } finally {
            TaskGate.shared.release(taskId)
        }
    }

    private suspend fun doWorkAdmitted(taskId: String): Result {
        setForeground(createForegroundInfo(taskId))
        // Watchdog bookkeeping: was THIS pass a forced retry
        // that has not been accounted for yet?
        val before = TaskStore(applicationContext).get(taskId)
        val forcedAt = before?.optLong("watchdogForcedAt", 0L) ?: 0L
        val handledAt = before?.optLong("watchdogHandledAt", 0L) ?: 0L
        val thisPassForced = forcedAt > handledAt
        val finalState = TaskRunner(applicationContext).runTask(taskId)
        if (thisPassForced) {
            // A forced retry must never fail silently: if it
            // STILL ends waiting, the record says so in work
            // terms (rendered on the status line by
            // TaskTransparency); if it moved, any older
            // watchdog note is cleared. Either way the force
            // is marked handled — the breaker re-arms from
            // the fresh updatedAt.
            TaskStore(applicationContext).update(taskId) {
                it.put("watchdogHandledAt", forcedAt)
                if (TaskStates.isWaiting(finalState)) {
                    it.put(
                        "watchdogNote",
                        TaskWatchdog.FORCED_STILL_WAITING_NOTE
                    )
                } else {
                    it.remove("watchdogNote")
                }
            }
        }
        // v0.13: a task born in a chat reports its settled
        // state back INTO that chat (graded, deduplicated by
        // ChatTaskLink). Waiting states compose nothing — the
        // re-schedule below is their whole story.
        ChatTaskReporter(applicationContext).deliver(taskId, finalState)
        if (TaskStates.isWaiting(finalState)) {
            // Schedule the next run at the record's own
            // nextRetryAt — the earliest moment a route can
            // serve — instead of WorkManager's retry backoff,
            // whose escalation is exactly the task-level
            // backoff v1.0.2 abolished.
            val record = TaskStore(applicationContext).get(taskId)
            val retryAt = record?.optLong("nextRetryAt", 0L) ?: 0L
            val delayMs = (retryAt - System.currentTimeMillis())
                .coerceAtLeast(0L)
            enqueueDelayed(applicationContext, taskId, delayMs)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val taskId = inputData.getString(KEY_TASK_ID) ?: "task"
        return createForegroundInfo(taskId)
    }

    private fun createForegroundInfo(taskId: String): ForegroundInfo {
        val channelId = "lille_tasks"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Lille tasks", NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Lille is working")
            .setContentText("Task $taskId is running in the background.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        val notifId = 1000 + (taskId.hashCode() and 0x3FF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MODE = "mode"
        const val MODE_IDENTITY_RECHECK = "identity_recheck"

        fun uniqueName(taskId: String) = "lille-task-$taskId"

        /** Enqueue an identity re-check (v1.0.9) under its
         *  OWN unique name — it must never REPLACE (or be
         *  replaced by) the task's own pending run: the
         *  read and the run are different passes over the
         *  same record. */
        fun enqueueIdentityRecheck(context: Context, taskId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_TASK_ID to taskId,
                        KEY_MODE to MODE_IDENTITY_RECHECK
                    )
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "lille-idcheck-$taskId",
                ExistingWorkPolicy.REPLACE, request
            )
        }

        /** Enqueue (or re-enqueue) background continuation for a task. */
        fun enqueue(context: Context, taskId: String) {
            enqueueDelayed(context, taskId, 0L)
        }

        /** Enqueue continuation after [delayMs] — the waiting
         *  state's own schedule, taken from the record's
         *  nextRetryAt. REPLACE policy means a fresh enqueue
         *  (a wake, a boot pass, a user action) supersedes a
         *  pending delayed run, never duplicates it. The
         *  backoff criteria remain only as a floor for the
         *  TaskGate contention retry inside doWork, which is
         *  a concurrency wait, not a route rest. */
        fun enqueueDelayed(context: Context, taskId: String, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(taskId), ExistingWorkPolicy.REPLACE, request
            )
        }

        /** WAKE ON NEW CAPACITY (v1.0.2): new brain capacity
         *  just landed (an own key saved in Settings). Every
         *  waiting task gets an expedited retry NOW — a rest
         *  timed against the old capacity must not keep
         *  running when the new route could carry the work.
         *  The runner re-derives the rest from live per-route
         *  state, so a task whose routes are genuinely all
         *  still resting simply re-schedules itself. */
        fun wakeWaitingTasks(context: Context) {
            val ids = TaskStates.waitingTaskIds(
                TaskStore(context).all()
            )
            for (id in ids) enqueue(context, id)
        }

        /** FORCE a waiting task to retry NOW (v1.0.2
         *  watchdog + the user's Run now): the stored wait
         *  state is treated as suspect and cleared BEFORE
         *  the pass — nextRetryAt and every per-route
         *  rest/pacing field — so the retry is a genuinely
         *  fresh all-routes attempt, not a re-read of the
         *  state that may have caused the stuckness. The
         *  force is stamped on the record; doWorkAdmitted
         *  accounts for it (visible note if it still ends
         *  waiting). */
        fun enqueueForced(context: Context, taskId: String) {
            val store = TaskStore(context)
            val record = store.get(taskId) ?: return
            if (!TaskStates.isWaiting(record.optString("state"))) return
            store.update(taskId) {
                it.remove("nextRetryAt")
                it.remove("routeRestUntil")
                it.remove("routeStreaks")
                it.remove("routeLastCallAt")
                it.put("watchdogForcedAt", System.currentTimeMillis())
                it.put(
                    "watchdogForces",
                    record.optInt("watchdogForces", 0) + 1
                )
            }
            enqueue(context, taskId)
        }

        /** One watchdog sweep (v1.0.2): expired waits get an
         *  immediate pass (the app-open kick); stale waits —
         *  unmoved beyond TaskWatchdog.STALE_MS — get a
         *  FORCED retry ignoring their stored wait state.
         *  Called on app open (MainActivity.onResume) and at
         *  the start of every worker pass. Route availability:
         *  the keyless pair is always configured (the
         *  no-keys doctrine), so a route is always available
         *  to force with. */
        fun watchdogPass(context: Context, excluding: String? = null) {
            val store = TaskStore(context)
            val tasks = store.all()
            val now = System.currentTimeMillis()
            val stale = TaskWatchdog.staleWaitIds(
                tasks, now, routeAvailable = true
            ).toSet()
            for (id in TaskWatchdog.expiredWaitIds(tasks, now)) {
                if (id == excluding || id in stale) continue
                enqueue(context, id)
            }
            for (id in stale) {
                if (id == excluding) continue
                enqueueForced(context, id)
            }
            // Identity re-checks owed (v1.0.9): a record
            // whose latest sign-in read couldn't conclude
            // gets the read retried on the watchdog's own
            // schedule — the sign-in never stays unknown
            // just because one page wouldn't say.
            for (id in TaskWatchdog.identityRecheckIds(tasks, now)) {
                if (id == excluding) continue
                enqueueIdentityRecheck(context, id)
            }
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(taskId))
        }
    }
}
