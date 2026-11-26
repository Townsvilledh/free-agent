package org.freeagent.app

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The PARKED_AT_WALL handoff screen (v0.9, WALLS.md protocol).
 *
 * When a browser task parks at a human-only wall, this screen
 * shows Lille's parked WebView session FULL-SCREEN — the exact
 * page, form state and all, nothing reloaded — so Ethuram can
 * take the ONE named step (pass the human check) inside her
 * session. "Done — hand back to Lille" moves the task
 * parked_at_wall -> running and re-enqueues the worker; she
 * re-reads the page and finishes the task herself.
 *
 * While this screen is up, a page file chooser with nothing
 * staged opens the real system picker (the runner's staged
 * uploads take precedence when one is staged).
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }

    private var taskId: String? = null
    private var store: TaskStore? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingChooserCallback
            pendingChooserCallback = null
            callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        store = TaskStore(this)

        val stepView = findViewById<TextView>(R.id.wallStepText)
        val task = taskId?.let { store?.get(it) }
        val step = task?.optString("wallStep") ?: ""
        stepView.text = if (step.isNotEmpty()) {
            step
        } else {
            "Lille is parked in her browser session. When you are " +
                "done here, hand the session back to her."
        }

        BrowserSession.fileChooserHook = { callback ->
            pendingChooserCallback = callback
            pickFile.launch(arrayOf("*/*"))
        }
        val container = findViewById<ViewGroup>(R.id.browserContainer)
        BrowserSession.attachTo(this, container)

        findViewById<Button>(R.id.handBackButton).setOnClickListener {
            handBack()
        }
        findViewById<Button>(R.id.closeBrowserButton).setOnClickListener {
            // Just looking: the task stays parked; nothing resumes.
            finish()
        }
    }

    private fun handBack() {
        val id = taskId
        val st = store
        if (id != null && st != null) {
            val state = st.get(id)?.optString("state") ?: ""
            if (state == TaskStates.PARKED_AT_WALL &&
                TaskStates.canTransition(state, TaskStates.RUNNING)
            ) {
                st.update(id, TaskStates.RUNNING) {
                    it.put("handedBackAt", System.currentTimeMillis())
                }
                TaskWorker.enqueue(this, id)
                Toast.makeText(
                    this,
                    "Handed back — Lille is resuming the task.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        finish()
    }

    override fun onDestroy() {
        BrowserSession.fileChooserHook = null
        pendingChooserCallback?.onReceiveValue(null)
        pendingChooserCallback = null
        BrowserSession.detach()
        super.onDestroy()
    }

    /** The WebChromeClient callback awaiting a picker result. */
    private var pendingChooserCallback:
        android.webkit.ValueCallback<Array<Uri>>? = null
}
