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
 * - Waiting states (network/route) return Result.retry() with
 *   WorkManager backoff; the checkpoint in the TaskStore means the
 *   retry RESUMES the task instead of restarting it.
 * - WorkManager's own queue survives reboot; BootReceiver also
 *   re-queues any task left in a resumable state.
 */
class TaskWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        setForeground(createForegroundInfo(taskId))
        val finalState = TaskRunner(applicationContext).runTask(taskId)
        // v0.13: a task born in a chat reports its settled
        // state back INTO that chat (graded, deduplicated by
        // ChatTaskLink). Waiting states compose nothing — the
        // retry below is their whole story.
        ChatTaskReporter(applicationContext).deliver(taskId, finalState)
        return if (TaskStates.isWaiting(finalState)) Result.retry() else Result.success()
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
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(taskId), ExistingWorkPolicy.REPLACE, request
            )
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(taskId))
        }
    }
}
