package org.freeagent.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * FreeAgent — single-activity chat UI (scaffold).
 *
 * v0.2 scope: chat screen stub. Sending a message currently echoes a
 * placeholder reply; wiring to the provider router (AgentRouter ->
 * Groq fallback, with on-device privacy routing + redaction, see
 * APP.md / SECURITY.md) lands in the next milestone.
 *
 * Keys are NEVER handled here: they live in the Keystore-backed
 * encrypted vault (Settings screen), and leave the device only to
 * their own provider.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var transcript: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.messageInput)
        findViewById<Button>(R.id.sendButton).setOnClickListener { send() }

        appendLine("FreeAgent", "Scaffold build. Add your AgentRouter and Groq keys in Settings (encrypted vault) — chat wiring lands in v0.2.")
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        appendLine("You", text)
        input.text.clear()
        // Placeholder local logic — replaced by the agent endpoint /
        // on-device router call in v0.2. No network call happens here.
        appendLine("FreeAgent", "(placeholder) Router not wired yet in the scaffold build.")
    }

    private fun appendLine(who: String, text: String) {
        transcript.append("\n$who: $text\n")
    }
}
