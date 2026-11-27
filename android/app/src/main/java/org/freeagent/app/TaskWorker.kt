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
        // Bounded concurrency (v1.0.1, TaskGate): at most
        // two task runs progress at once, and the exclusive
        // browser lane admits one browser run at a time. A
        // denied run retries later WITHOUT touching the
        // record — the task's state and checkpoint are
        // exactly as they were, so serialisation on the
        // lane never fails either task.
        val record = TaskStore(applicationContext).get(taskId)
            ?: return Result.failure()
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
        val finalState = TaskRunner(applicationContext).runTask(taskId)
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

        fun uniqueName(taskId: String) = "lille-task-$taskId"

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

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(taskId))
        }
    }
}
