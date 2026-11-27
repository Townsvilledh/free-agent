package org.freeagent.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Her agent state (v1.0.4): ONE JSON document in the app's
 * private storage (filesDir/lille_agent_state.json) holding
 * the sections the 1.0.4 mechanisms persist —
 *
 * - "memory"    — AgentMemory's document (core blocks + journal)
 * - "skills"    — SkillStore's document (her self-written skills)
 * - "cards"     — SiteCards' document (per-site cheat-sheets)
 * - "heartbeat" — the HEARTBEAT.md checklist text
 * - "recipes"   — the recipe library ({name: recipeJsonText})
 * - "jobs"      — ScheduledJobs' array text (cron lane)
 *
 * One file, rewritten atomically like every other store,
 * because the BACKUP treats it as one more raw document:
 * memory, skills, cards, checklist, recipes and schedules
 * ride the encrypted backup exactly like chats and tasks
 * (BackupManager gathers rawText(); restore replaceRaw()).
 * Each section is exposed to its pure component through a
 * [TextIO] door, so the components stay Android-free and
 * JVM-tested.
 */
class AgentStateStore(context: Context) {
    private val file = File(context.filesDir, "lille_agent_state.json")

    companion object {
        const val SECTION_MEMORY = "memory"
        const val SECTION_SKILLS = "skills"
        const val SECTION_CARDS = "cards"
        const val SECTION_HEARTBEAT = "heartbeat"
        const val SECTION_RECIPES = "recipes"
        const val SECTION_JOBS = "jobs"

        /** The checklist a fresh install starts with: a
         *  comment-only HEARTBEAT.md — zero items, so the
         *  heartbeat's skip discipline holds until the user
         *  (or Lille) writes a real item. */
        const val HEARTBEAT_TEMPLATE =
            "# Lille's heartbeat checklist\n" +
                "# Lines starting with - [ ] are checked on every " +
                "proactive pass.\n" +
                "# Add \"due: YYYY-MM-DD\" to an item to make it " +
                "surface on its day.\n"
    }

    @Synchronized
    private fun root(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun write(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun readSection(name: String): String? {
        val sections = root().optJSONObject("sections") ?: return null
        return if (sections.has(name)) sections.optString(name) else null
    }

    @Synchronized
    fun writeSection(name: String, text: String) {
        val root = root()
        val sections = root.optJSONObject("sections") ?: JSONObject()
        sections.put(name, text)
        root.put("sections", sections)
        write(root)
    }

    /** The TextIO door a pure component persists through. */
    fun io(section: String): TextIO = object : TextIO {
        override fun read(): String? = readSection(section)
        override fun write(text: String) = writeSection(section, text)
    }

    /** Backup hooks (v1.0.4): the raw store document, out
     *  and in — restore writes it back byte-for-byte. */
    @Synchronized
    fun rawText(): String? =
        if (file.exists()) try { file.readText() } catch (e: Exception) { null } else null

    @Synchronized
    fun replaceRaw(text: String) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
