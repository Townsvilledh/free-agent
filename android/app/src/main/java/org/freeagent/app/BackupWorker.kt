package org.freeagent.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Backup safety net (v0.11, BACKUP.md): a periodic pass that
 * flushes an owed backup when it can. The honest limit, by
 * design: the encryption key lives in memory only, so this
 * worker can write only while the app process is alive with
 * an unlocked key (the common case — Android keeps Lille's
 * process around, and every chat change already writes
 * immediately then). The deterministic triggers are the
 * change-driven writes in BackupManager.requestBackup; this
 * net catches whatever they missed while the key was held.
 * It never asks for a signature and never holds a key itself.
 */
class BackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = BackupPrefs(applicationContext)
        if (prefs.setupDone && prefs.dirty && BackupKeyHolder.hasKey()) {
            withContext(Dispatchers.IO) {
                BackupManager.flushNow(applicationContext)
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "lille-backup-flush"

        /** Idempotent: safe to call on every app start / boot. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                12, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
