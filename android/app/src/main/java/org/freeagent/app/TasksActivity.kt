package org.freeagent.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Tasks screen (v0.4; proactivity added v0.7): durable background
 * tasks. Create a task, run it, then close the app / lock the
 * phone / lose the network — the task checkpoints after every step
 * and resumes by itself (OFFLINE.md). Code-execution tasks are
 * marked needs_runner and exported as specs for the GitHub Actions
 * lane, never faked.
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
        data class ReportRow(val report: JSONObject) : Row()
        data class CommitmentRow(val cmt: JSONObject) : Row()
    }

    private lateinit var store: TaskStore
    private lateinit var proactive: ProactiveStore
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
            if (text.isEmpty()) return@setOnClickListener
            val task = store.create(text)
            selectedId = task.optString("id")
            input.text.clear()
            Toast.makeText(this, "Task queued — press RUN, then go offline if you like.",
                Toast.LENGTH_LONG).show()
            refresh()
        }
        findViewById<Button>(R.id.runTaskButton).setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (state == TaskStates.FAILED) store.update(id, TaskStates.QUEUED)
            TaskWorker.enqueue(this, id)
            Toast.makeText(this, "Task running in the background.", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.pauseTaskButton).setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (TaskStates.canTransition(state, TaskStates.PAUSED)) {
                store.update(id, TaskStates.PAUSED)
            }
            refresh()
        }
        findViewById<Button>(R.id.resumeTaskButton).setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (state == TaskStates.FAILED) store.update(id, TaskStates.QUEUED)
            TaskWorker.enqueue(this, id)
            refresh()
        }
        findViewById<Button>(R.id.cancelTaskButton).setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            val state = store.get(id)?.optString("state") ?: return@setOnClickListener
            if (TaskStates.canTransition(state, TaskStates.CANCELLED)) {
                store.update(id, TaskStates.CANCELLED)
            }
            TaskWorker.cancel(this, id)
            refresh()
        }
        findViewById<Button>(R.id.refreshTasksButton).setOnClickListener { refresh() }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows.getOrNull(position)) {
                is Row.TaskRow -> {
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

    private fun refresh() {
        tasks = store.all().reversed()
        val built = mutableListOf<Row>()
        tasks.forEach { built.add(Row.TaskRow(it)) }
        val reports = proactive.reports().reversed().take(20)
        if (reports.isNotEmpty()) {
            built.add(Row.Header("— While you were away —"))
            reports.forEach { built.add(Row.ReportRow(it)) }
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
                    "[${row.task.optString("state")}] ${row.task.optString("text").take(60)}"
                is Row.ReportRow ->
                    "✉ [${row.report.optString("status")}] ${row.report.optString("title").take(55)}"
                is Row.CommitmentRow ->
                    "◔ [${row.cmt.optString("state")}] ${row.cmt.optString("title").take(55)}"
            }
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        showDetail()
    }

    private fun showDetail() {
        val id = selectedId
        val task = id?.let { store.get(it) }
        detail.text = if (task == null) {
            "Tap a task to see its state. Tasks checkpoint after every step and " +
                "resume after the app closes, the network drops, or the phone reboots."
        } else {
            buildString {
                append("State: ${task.optString("state")}\n")
                append("Steps done: ${task.optInt("steps")}\n")
                append("Task: ${task.optString("text")}\n")
                val summary = task.optString("summary")
                if (summary.isNotEmpty()) append("Summary: $summary\n")
                val note = task.optString("note")
                if (note.isNotEmpty()) append("Note: $note\n")
                val retry = task.optLong("nextRetryAt")
                if (retry > System.currentTimeMillis()) {
                    append("Waiting — resumes automatically when the route/network returns.\n")
                }
            }
        }
    }
}
