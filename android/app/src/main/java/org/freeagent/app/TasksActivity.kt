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
 * Tasks screen (v0.4): durable background tasks. Create a task, run
 * it, then close the app / lock the phone / lose the network — the
 * task checkpoints after every step and resumes by itself
 * (OFFLINE.md). Code-execution tasks are marked needs_runner and
 * exported as specs for the GitHub Actions lane, never faked.
 */
class TasksActivity : AppCompatActivity() {

    private lateinit var store: TaskStore
    private lateinit var listView: ListView
    private lateinit var detail: TextView
    private lateinit var input: EditText
    private var selectedId: String? = null
    private var tasks: List<JSONObject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tasks)
        store = TaskStore(this)
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
            selectedId = tasks.getOrNull(position)?.optString("id")
            showDetail()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        tasks = store.all().reversed()
        val labels = tasks.map {
            "[${it.optString("state")}] ${it.optString("text").take(60)}"
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
