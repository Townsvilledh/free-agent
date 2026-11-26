package org.freeagent.app

/**
 * Bounded task concurrency + the exclusive browser lane
 * (2026-11-26, multitasking ruling).
 *
 * Before this gate, durable tasks effectively ran one at
 * a time in the user's experience. The gate admits up to
 * [MAX_RUNNING] task runs at once — interleaved steps,
 * independent checkpoints (the store already checkpoints
 * per task) — with ONE hard exclusion: the device browser
 * lane. BrowserSession is a single WebView with a single
 * page state; a browser task owns it for its whole run,
 * and a second browser task's run does not start until
 * the lane is free. Brain/file/search tasks never touch
 * the lane and are never blocked by it.
 *
 * The gate is admission only — it touches no task record.
 * A worker denied admission simply retries later
 * (WorkManager backoff), leaving the task's state and
 * checkpoint exactly as they were. Ownership is per run:
 * a run that settles into a waiting state releases
 * everything, and its retry re-acquires.
 *
 * Pinned by TaskGateTest: two tasks interleaving to
 * completion, a browser run and a brain run overlapping,
 * two browser runs serialising without either failing.
 */
class TaskGate {

    companion object {
        const val MAX_RUNNING = 2

        /** The process-wide gate the workers admit through. */
        val shared = TaskGate()
    }

    private val running = mutableSetOf<String>()
    private var browserOwner: String? = null

    /**
     * Try to admit one run of [taskId]. [needsBrowser] is
     * the task's kind (browser tasks hold the lane for the
     * run). True = admitted (release when the run ends).
     */
    @Synchronized
    fun tryAcquire(taskId: String, needsBrowser: Boolean): Boolean {
        if (taskId in running) {
            // A re-entrant admission for the same run: the
            // browser lane must still be (or become) its own.
            if (needsBrowser && browserOwner != null &&
                browserOwner != taskId
            ) {
                return false
            }
            if (needsBrowser) browserOwner = taskId
            return true
        }
        if (running.size >= MAX_RUNNING) return false
        if (needsBrowser && browserOwner != null) return false
        running.add(taskId)
        if (needsBrowser) browserOwner = taskId
        return true
    }

    /** End one run: frees its slot and, if it held it, the
     *  browser lane. */
    @Synchronized
    fun release(taskId: String) {
        running.remove(taskId)
        if (browserOwner == taskId) browserOwner = null
    }

    @Synchronized
    fun runningCount(): Int = running.size

    @Synchronized
    fun browserOwnerTask(): String? = browserOwner

    @Synchronized
    fun isRunning(taskId: String): Boolean = taskId in running
}
