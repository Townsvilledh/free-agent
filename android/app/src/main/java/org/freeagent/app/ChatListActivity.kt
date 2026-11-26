package org.freeagent.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/**
 * Chat list (v0.11): every saved conversation, newest
 * activity first. Tap to open (MainActivity is reused via
 * CLEAR_TOP and re-renders the newly active chat); the trash
 * button deletes after a confirm — deletion is forever, and
 * the confirm says so. The list is rebuilt from ChatStore on
 * every resume, so it can never show a stale world.
 */
class ChatListActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var chatStore: ChatStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)
        chatStore = ChatStore(this)
        list = findViewById(R.id.chatList)
        findViewById<Button>(R.id.newChatButton2).setOnClickListener {
            chatStore.createChat()
            openChat()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val root = chatStore.root()
        val activeId = ChatData.activeId(root)
        val chats = ChatData.chatsByRecency(root)
        if (chats.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No chats yet — start one with New chat."
            empty.setTextColor(Color.parseColor("#B9B0A2"))
            empty.textSize = 15f
            list.addView(empty)
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT
        )
        for (chat in chats) {
            val id = chat.optString("id")
            val isActive = id == activeId
            val block = LinearLayout(this)
            block.orientation = LinearLayout.VERTICAL
            block.setBackgroundResource(R.drawable.bg_bubble_lille)
            block.setPadding(dp(14), dp(10), dp(14), dp(10))
            val blockParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            blockParams.bottomMargin = dp(8)
            block.layoutParams = blockParams

            val title = TextView(this)
            title.text = chat.optString("title", ChatData.DEFAULT_TITLE) +
                if (isActive) "  ·  current" else ""
            title.setTextColor(Color.parseColor("#F5EFE7"))
            title.textSize = 16f
            block.addView(title)

            val count = ChatData.messageCount(root, id)
            val updated = chat.optLong("updatedAt", 0L)
            // CHAT IS THE ENGINE: a chat holding live work
            // says so on its row — a plain state mark
            // (ChatWorkMark), so a working / waiting /
            // needs-you chat is recognisable at a glance
            // without opening it. There is no Tasks screen;
            // this list and the threads are where work shows.
            val workMark = StatusProbe.latestTaskForChat(
                TaskStore(this).all(), id
            )?.let { ChatWorkMark.markFor(it.optString("state")) }
            val meta = TextView(this)
            meta.text = "$count message${if (count == 1) "" else "s"} · " +
                (if (updated > 0) dateFormat.format(Date(updated)) else "") +
                (workMark?.let { "  $it" } ?: "")
            meta.setTextColor(Color.parseColor("#8A8378"))
            meta.textSize = 12f
            block.addView(meta)

            val delete = Button(this)
            delete.text = getString(R.string.delete_chat)
            val deleteParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            deleteParams.gravity = Gravity.END
            delete.layoutParams = deleteParams
            delete.setOnClickListener { confirmDelete(id, chat.optString("title")) }
            block.addView(delete)

            block.setOnClickListener {
                chatStore.setActive(id)
                openChat()
            }
            list.addView(block)
        }
    }

    private fun confirmDelete(id: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete this chat?")
            .setMessage(
                "\"$title\" and all its messages will be deleted from this " +
                    "phone. If a backup already holds it, the next automatic " +
                    "backup replaces that too. This cannot be undone."
            )
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                chatStore.deleteChat(id)
                render()
            }
            .show()
    }

    private fun openChat() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
