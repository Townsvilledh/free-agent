package org.freeagent.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Tasks screen (v0.4; proactivity added v0.7; device browser lane
 * v0.9): durable background tasks. Create a task, run it, then
 * close the app / lock the phone / lose the network — the task
 * checkpoints after every step and resumes by itself
 * (OFFLINE.md). Code-execution tasks are marked needs_runner and
 * exported as specs for the GitHub Actions lane, never faked.
 *
 * v0.9 surfaces:
 * - "Lille needs you" — tasks PARKED_AT_WALL at a human-only
 *   wall in her on-device browser. Tapping one (or the "Open
 *   Lille's browser" button) opens her parked session
 *   full-screen in BrowserActivity: Ethuram takes the ONE named
 *   step there and hands back; she resumes alone.
 * - "Lille: create my X account" — the packaged acceptance task
 *   (XAccountTask), one tap to create + start on the device lane.
 *   OWNER INSTANCES ONLY (v0.10.1, OwnerGate): her X account is
 *   hers alone, so the button is rendered only when the verified
 *   Fluid login identity is the owner's — for everyone else it is
 *   simply absent, and the task cannot be created from the UI
 *   (XLANE.md, "Hers alone").
 * - Report / Share — the graded spec report for a task
 *   (XAccountTask.reportFor for packaged tasks), readable here
 *   and shareable out of the app.
 *
 * Below the tasks, the same list carries the Proactivity Law's
 * surfaces (PROACTIVITY.md): "While you were away" — reports the
 * periodic proactive pass wrote WITHOUT being asked (a watched
 * result landed, a queued task finished) — and "Commitments",
 * the register of results Lille is watching for.
 */
class TasksActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class TaskRow(val task: JSONObject) : Row()
        data class ParkedRow(val task: JSONObject) : Row()
        data class ReportRow(val report: JSONObject) : Row()
        data class CommitmentRow(val cmt: JSONObject) : Row()
    }

    private lateinit var store: TaskStore
    private lateinit var proactive: ProactiveStore
    private lateinit var createXButton: Button
    private lateinit var listView: ListView
    private lateinit var detail: TextView
    private lateinit var input: EditText
    private var selectedId: String? = null
    private var tasks: List<JSONObject> = emptyList()
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tasks)
        store = TaskStore(this)
        proactive = ProactiveStore(this)
        // Make sure the periodic proactive pass is armed (v0.7).
        ProactiveWorker.schedule(this)
        listView = findViewById(R.id.taskList)
        detail = findViewById(R.id.taskDetail)
        input = findViewById(R.id.taskInput)

        findViewById<Button>(R.id.createTaskButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Describe the task first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val task = store.create(text)
            selectedId = task.optString("id")
            input.text.clear()
            Toast.makeText(this, "Task queued — press RUN, then go offline if you like.",
                Toast.LENGTH_LONG).show()
            refresh()
        }
        findViewById<Button>(R.id.runTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (state == TaskStates.FAILED) store.update(id, TaskStates.QUEUED)
            TaskWorker.enqueue(this, id)
            Toast.makeText(this, "Task running in the background.", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.pauseTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (TaskStates.canTransition(state, TaskStates.PAUSED)) {
                store.update(id, TaskStates.PAUSED)
            }
            refresh()
        }
        findViewById<Button>(R.id.resumeTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (state == TaskStates.FAILED) store.update(id, TaskStates.QUEUED)
            TaskWorker.enqueue(this, id)
            refresh()
        }
        findViewById<Button>(R.id.cancelTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (TaskStates.canTransition(state, TaskStates.CANCELLED)) {
                store.update(id, TaskStates.CANCELLED)
            }
            TaskWorker.cancel(this, id)
            refresh()
        }
        findViewById<Button>(R.id.refreshTasksButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.disconnectFluidButton).setOnClickListener {
            // v0.10 (LOGIN.md): end the WalletConnect session and
            // forget the verified Fluid identity, so the login
            // gate can be re-tested from scratch at next launch.
            // Background tasks are NOT touched — the gate guards
            // the UI only, never the workers.
            val identityStore = FluidIdentityStore(this)
            FluidLogin.init(this)
            FluidLogin.disconnectSessions(identityStore, forgetIdentity = true)
            // The identity is gone, so owner-only surfaces go too.
            applyOwnerGate()
            Toast.makeText(
                this,
                "Fluid name disconnected — Lille will ask for a fresh " +
                    "connection next time she opens.",
                Toast.LENGTH_LONG
            ).show()
        }
        createXButton = findViewById(R.id.createXTaskButton)
        createXButton.setOnClickListener {
            val existing = store.all().firstOrNull {
                it.optString("title") == XAccountTask.TITLE &&
                    !TaskStates.isTerminal(it.optString("state"))
            }
            if (existing != null) {
                selectedId = existing.optString("id")
                Toast.makeText(this,
                    "Her X-account task is already on the list — tap RUN if it is queued.",
                    Toast.LENGTH_LONG).show()
            } else {
                val task = XAccountTask.create(this)
                selectedId = task.optString("id")
                TaskWorker.enqueue(this, selectedId!!)
                Toast.makeText(this,
                    "Lille is on it — she is creating her X account in her own " +
                        "browser on this phone. If X asks for a human check, a " +
                        "'Lille needs you' card will appear here.",
                    Toast.LENGTH_LONG).show()
            }
            refresh()
        }
        findViewById<Button>(R.id.openBrowserButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_TASK_ID, id)
            )
        }
        findViewById<Button>(R.id.reportTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val task = store.get(id) ?: return@setOnClickListener
            detail.text = reportFor(task)
        }
        findViewById<Button>(R.id.shareTaskButton).setOnClickListener {
            val id = selectedOrToast() ?: return@setOnClickListener
            val task = store.get(id) ?: return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, reportFor(task))
            startActivity(Intent.createChooser(send, "Share Lille's report"))
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows.getOrNull(position)) {
                is Row.TaskRow -> {
                    selectedId = row.task.optString("id")
                    showDetail()
                }
                is Row.ParkedRow -> {
                    selectedId = row.task.optString("id")
                    showDetail()
                }
                is Row.ReportRow -> {
                    detail.text = buildString {
                        append("[${row.report.optString("status")}] ")
                        append("${row.report.optString("title")}\n")
                        append(row.report.optString("body"))
                    }
                }
                is Row.CommitmentRow -> {
                    detail.text = buildString {
                        append("Commitment [${row.cmt.optString("state")}]: ")
                        append("${row.cmt.optString("title")}\n")
                        append("Watching: ${row.cmt.optString("check")}\n")
                        append("Checks executed: ${row.cmt.optInt("checkCount")}")
                    }
                }
                else -> Unit
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Action buttons act on the selected task; with nothing
     *  selected they used to swallow the tap silently, which
     *  reads as a dead button to a first-time user (v0.10
     *  distribution walk). Now they say what to do. */
    private fun selectedOrToast(): String? {
        val id = selectedId
        if (id == null) {
            Toast.makeText(
                this, "Tap a task in the list first.", Toast.LENGTH_SHORT
            ).show()
        }
        return id
    }

    /** Owner gate (v0.10.1): the X-account creation button is
     *  rendered ONLY when the verified Fluid login identity is
     *  an owner identity (OwnerGate) — Lille's own X account is
     *  created and managed from the owner's instance alone.
     *  For any other login, and with no verified login at all,
     *  the button is GONE — not greyed, not mentioned — and the
     *  task cannot be created from this UI. Re-applied on every
     *  refresh so a disconnect hides it immediately. */
    private fun applyOwnerGate() {
        val username = FluidIdentityStore(this).load()?.username
        createXButton.visibility =
            if (OwnerGate.canCreateXAccountTask(username)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun refresh() {
        applyOwnerGate()
        tasks = store.all().reversed()
        val built = mutableListOf<Row>()
        val parked = tasks.filter {
            TaskStates.isParked(it.optString("state"))
        }
        if (parked.isNotEmpty()) {
            built.add(Row.Header("— Lille needs you —"))
            parked.forEach { built.add(Row.ParkedRow(it)) }
        }
        tasks.filterNot { TaskStates.isParked(it.optString("state")) }
            .forEach { built.add(Row.TaskRow(it)) }
        val reports = proactive.reports().reversed().take(20)
        if (reports.isNotEmpty()) {
            // Direction 4: the debrief is ordered Needs you →
            // Failed → Finished, as distinct sections.
            built.add(Row.Header("— While you were away —"))
            for (rank in DebriefOrder.NEEDS_YOU..DebriefOrder.FINISHED) {
                val group = reports.filter {
                    DebriefOrder.rank(it.optString("status")) == rank
                }
                if (group.isNotEmpty()) {
                    built.add(
                        Row.Header("— ${DebriefOrder.sectionTitle(rank)} —")
                    )
                    group.forEach { built.add(Row.ReportRow(it)) }
                }
            }
        }
        val cmts = proactive.commitments().reversed()
        if (cmts.isNotEmpty()) {
            built.add(Row.Header("— Commitments (results Lille is watching) —"))
            cmts.forEach { built.add(Row.CommitmentRow(it)) }
        }
        rows = built
        val labels = rows.map { row ->
            when (row) {
                is Row.Header -> row.text
                is Row.TaskRow ->
                    "[${row.task.optString("state")}] ${taskLabel(row.task)}"
                is Row.ParkedRow ->
                    "⚠ LILLE NEEDS YOU — ${taskLabel(row.task)}"
                is Row.ReportRow ->
                    "✉ [${row.report.optString("status")}] ${row.report.optString("title").take(55)}"
                is Row.CommitmentRow ->
                    "◔ [${row.cmt.optString("state")}] ${row.cmt.optString("title").take(55)}"
            }
        }
        listView.adapter = RowAdapter(labels)
        showDetail()
    }

    /** Rows carry the mascot state icon (Direction 1): a task
     *  row shows Lille in the state the task is in — working,
     *  waiting, done, stuck — drawn as a compound drawable on
     *  the row's text. Static swaps only. */
    private inner class RowAdapter(labels: List<String>) :
        ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val res = when (val row = rows.getOrNull(position)) {
                is Row.TaskRow -> mascotRes(
                    MascotStates.forTaskState(row.task.optString("state"))
                )
                is Row.ParkedRow -> mascotRes(MascotStates.Mascot.STUCK)
                else -> 0
            }
            view.setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)
            view.compoundDrawablePadding = 20
            return view
        }
    }

    private fun mascotRes(mascot: MascotStates.Mascot): Int =
        resources.getIdentifier(
            MascotStates.drawableName(mascot), "drawable", packageName
        )

    private fun taskLabel(task: JSONObject): String {
        val title = task.optString("title")
        return (title.ifEmpty { task.optString("text") }).take(60)
    }

    /** The graded report for a task: the spec report for
     *  packaged tasks (criteria + recorded evidence), a plain
     *  state report for the rest. */
    private fun reportFor(task: JSONObject): String {
        if (task.has("criteria")) return XAccountTask.reportFor(task)
        return buildString {
            append("LILLE — TASK REPORT\n")
            append("State: ${task.optString("state")}\n")
            val lane = task.optString("lane")
            if (lane.isNotEmpty()) append("Lane: $lane\n")
            append("Task: ${task.optString("text")}\n")
            val summary = task.optString("summary")
            if (summary.isNotEmpty()) append("Summary: $summary\n")
            val note = task.optString("note")
            if (note.isNotEmpty()) append("Note: $note\n")
            append("Steps done: ${task.optInt("steps")}\n")
            append("Cost: $0 — keyless routes only")
        }
    }

    private fun showDetail() {
        val id = selectedId
        val task = id?.let { store.get(it) }
        detail.text = if (task == null) {
            "Tap a task to see its state. Tasks checkpoint after every step and " +
                "resume after the app closes, the network drops, or the phone reboots."
        } else {
            buildString {
                val state = task.optString("state")
                append("State: $state\n")
                append("Steps done: ${task.optInt("steps")}\n")
                // Mission log (Direction 2): how long it has
                // been at it, what it is doing now in plain
                // words, and the checklist of steps its own
                // checkpointed trace proves are done. No fake
                // percentages, no invented pending steps.
                val createdAt = task.optLong("createdAt")
                    .takeIf { it > 0 } ?: task.optLong("updatedAt")
                val endMs = if (TaskStates.isTerminal(state)) {
                    task.optLong("updatedAt")
                } else {
                    System.currentTimeMillis()
                }
                val elapsed = MissionLog.elapsedLabel(createdAt, endMs)
                if (elapsed.isNotEmpty()) {
                    append(
                        if (TaskStates.isTerminal(state)) "Took: $elapsed\n"
                        else "Elapsed: $elapsed\n"
                    )
                }
                val action = MissionLog.currentAction(
                    state, task.optString("note")
                )
                if (action.isNotEmpty()) append("Now: $action\n")
                val tools = mutableListOf<String>()
                val msgs = task.optJSONArray("messages")
                if (msgs != null) {
                    for (i in 0 until msgs.length()) {
                        val c = msgs.getJSONObject(i).optString("content")
                        if (c.startsWith("Tool result (")) {
                            tools.add(
                                c.removePrefix("Tool result (")
                                    .substringBefore(")")
                            )
                        }
                    }
                }
                val checklist = MissionLog.checklist(
                    tools, state == TaskStates.RUNNING, task.optInt("steps")
                )
                if (checklist.isNotEmpty()) {
                    append("Mission log:\n")
                    checklist.forEach { append("  $it\n") }
                }
                val lane = task.optString("lane")
                if (lane.isNotEmpty()) append("Lane: $lane\n")
                append("Task: ${taskLabel(task)}\n")
                if (TaskStates.isParked(task.optString("state"))) {
                    append("\n⚠ LILLE NEEDS YOU — one step:\n")
                    append("${task.optString("wallStep")}\n")
                    append("Wall: ${task.optString("wallKind")} on " +
                        "${task.optString("wallDomain")} — " +
                        "${task.optString("wallDetail")}\n")
                    append("Tap 'Open Lille's browser' to take the step in " +
                        "her session.\n")
                }
                val summary = task.optString("summary")
                if (summary.isNotEmpty()) append("Summary: $summary\n")
                val retry = task.optLong("nextRetryAt")
                if (retry > System.currentTimeMillis()) {
                    append("Waiting — resumes automatically when the route/network returns.\n")
                }
            }
        }
    }
}
