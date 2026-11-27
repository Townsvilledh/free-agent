package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * RECIPES (the Goose pattern, reimplemented): user-readable,
 * schedulable, self-checking task packages.
 *
 * A recipe is a small JSON document a user can read, edit,
 * share and schedule — it is not code. It names a job in
 * plain instructions, declares the parameters those
 * instructions take ({{param}} placeholders), lists the
 * exact tools the run may use, says how its success is
 * CHECKED (not merely claimed), how many times a failed
 * check may be retried, and optionally a schedule string
 * a scheduler (see ScheduledJobs) can bind to.
 *
 * The shape:
 *
 *   {"name":"morning-brief","title":"Morning brief",
 *    "instructions":"... {{date}} ...",
 *    "params":[{"name":"date","type":"string",
 *               "default":"today"}],
 *    "tools":["calendar_read","gmail_list","write_note"],
 *    "successCheck":{"kind":"note_contains",
 *                    "target":"morning-brief.txt",
 *                    "pattern":"Today"},
 *    "maxRetries":1,"schedule":"daily 07:00"}
 *
 * The laws this component enforces:
 *
 * - VALIDATE BEFORE ANYTHING RUNS. [validate] returns
 *   human-readable errors — a recipe that fails validation
 *   never reaches a runner, and the user is told why in
 *   words, not codes. [parse] is the gate: it returns the
 *   normalised recipe (defaults filled) or null.
 * - A CLOSED TOOL LIST. A recipe may only name tools from
 *   the known set below — the same names the runners
 *   dispatch. An unknown tool name is a validation error,
 *   never a runtime surprise: a recipe cannot quietly ask
 *   for a capability the app does not have.
 * - SUCCESS IS CHECKED, NOT CLAIMED. Every recipe carries
 *   a successCheck. "always" exists for jobs with no
 *   artefact to probe, and it is the explicit default —
 *   but a recipe that produces a note or a page should
 *   say what must be IN it, and [checkPasses] judges the
 *   probe text against that pattern. A missing pattern is
 *   a failed check, never a pass by absence.
 * - SUBSTITUTION IS HONEST. [substitute] replaces a
 *   {{param}} only when the recipe declares that param:
 *   the caller's value first, then the param's declared
 *   default, then the empty string. An unknown {{token}}
 *   is left exactly as written — a typo'd placeholder
 *   stays visible in the instructions instead of silently
 *   vanishing.
 *
 * Honest limits: this component validates and prepares
 * recipes; it does not run them, does not schedule them
 * (a schedule string is data here — ScheduledJobs owns
 * timing), and does not probe notes or pages itself
 * ([checkPasses] judges probe text the caller fetched).
 * The schedule string's grammar ("daily 07:00") is the
 * scheduler's concern, deliberately not parsed here.
 *
 * Pure Kotlin + org.json; pinned by RecipesTest.
 */
object Recipes {

    /** The tools a recipe may name — the closed set the
     *  runners actually dispatch. Keep in step with the
     *  tool dispatch; a name not here is not a tool. */
    val KNOWN_TOOLS = setOf(
        "browser_open", "browser_read", "browser_click",
        "browser_type", "browser_wait", "web_search",
        "write_note", "read_note", "list_notes",
        "calendar_read", "calendar_create",
        "gmail_list", "gmail_search", "gmail_read",
        "memory_read", "memory_search", "finish"
    )

    private val PARAM_TYPES = setOf("string", "number", "boolean")
    private val CHECK_KINDS = setOf("note_contains", "page_contains", "always")
    private val NAME_RE = Regex("[a-z0-9-]{1,48}")
    private val TOKEN_RE = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")

    /**
     * Human-readable validation errors for [obj]; an empty
     * list means the recipe is valid. Every error names
     * the field and the problem, so the list can be shown
     * to the recipe's author as-is.
     */
    fun validate(obj: JSONObject): List<String> {
        val errors = mutableListOf<String>()

        val name = obj.optString("name")
        if (name.isBlank()) {
            errors.add("name is missing or blank")
        } else if (!NAME_RE.matches(name)) {
            errors.add(
                "name \"$name\" is invalid: use 1-48 characters " +
                    "of lowercase letters, digits and hyphens"
            )
        }

        if (obj.optString("instructions").isBlank()) {
            errors.add("instructions are missing or blank")
        }

        val params = obj.optJSONArray("params")
        if (params != null) {
            for (i in 0 until params.length()) {
                val param = params.optJSONObject(i)
                if (param == null) {
                    errors.add("params[$i] is not an object")
                    continue
                }
                val paramName = param.optString("name")
                val paramType = param.optString("type")
                if (paramName.isBlank()) {
                    errors.add("params[$i] is missing a name")
                }
                if (paramType.isBlank()) {
                    errors.add(
                        "param \"$paramName\" is missing a type"
                    )
                } else if (paramType !in PARAM_TYPES) {
                    errors.add(
                        "param \"$paramName\" has unknown type " +
                            "\"$paramType\": use string, number or boolean"
                    )
                }
            }
        }

        val tools = obj.optJSONArray("tools")
        if (tools == null || tools.length() == 0) {
            errors.add("tools are missing or empty")
        } else {
            for (i in 0 until tools.length()) {
                val tool = tools.optString(i)
                if (tool !in KNOWN_TOOLS) {
                    errors.add("unknown tool \"$tool\" in tools")
                }
            }
        }

        val check = obj.optJSONObject("successCheck")
        if (check != null) {
            val kind = check.optString("kind")
            if (kind !in CHECK_KINDS) {
                errors.add(
                    "successCheck kind \"$kind\" is unknown: " +
                        "use note_contains, page_contains or always"
                )
            }
        }

        if (obj.has("maxRetries")) {
            val retries = obj.optInt("maxRetries", 0)
            if (retries < 0 || retries > 3) {
                errors.add(
                    "maxRetries $retries is outside 0..3"
                )
            }
        }

        return errors
    }

    /**
     * The recipe normalised — a copy of [obj] with defaults
     * filled (params [], maxRetries 0, successCheck
     * {"kind":"always"}) — or null when [validate] reports
     * any error. The input is never mutated.
     */
    fun parse(obj: JSONObject): JSONObject? {
        if (validate(obj).isNotEmpty()) return null
        val out = JSONObject(obj.toString())
        if (!out.has("params") || out.isNull("params")) {
            out.put("params", JSONArray())
        }
        if (!out.has("tools") || out.isNull("tools")) {
            out.put("tools", JSONArray())
        }
        if (!out.has("maxRetries") || out.isNull("maxRetries")) {
            out.put("maxRetries", 0)
        }
        if (!out.has("successCheck") || out.isNull("successCheck")) {
            out.put("successCheck", JSONObject().put("kind", "always"))
        }
        return out
    }

    /** The declared params of [recipe], by name. */
    private fun declaredParams(recipe: JSONObject): Map<String, JSONObject> {
        val out = mutableMapOf<String, JSONObject>()
        val params = recipe.optJSONArray("params") ?: return out
        for (i in 0 until params.length()) {
            val param = params.optJSONObject(i) ?: continue
            val name = param.optString("name")
            if (name.isNotEmpty()) out[name] = param
        }
        return out
    }

    /** A param's declared default as a string, or null
     *  when it declares none. JSON defaults may be
     *  non-strings (a number, a boolean); their plain
     *  string form is what lands in instruction text. */
    private fun defaultOf(param: JSONObject): String? {
        if (!param.has("default") || param.isNull("default")) return null
        val raw = param.opt("default")
        return raw?.toString()
    }

    /**
     * The recipe's instructions with each declared
     * {{param}} replaced: the caller's [values] first,
     * then the param's default, then the empty string.
     * Unknown {{tokens}} are left exactly as written.
     */
    /** The recipe's tool allowlist as a set — the runner's
     *  dispatch reads it per call (the allowlist is the
     *  recipe's contract, enforced at dispatch, not by
     *  prompt). */
    fun allowedTools(recipe: JSONObject): Set<String> {
        val arr = recipe.optJSONArray("tools") ?: return emptySet()
        return List(arr.length()) { arr.optString(it) }
            .filter { it.isNotEmpty() }.toSet()
    }

    fun substitute(recipe: JSONObject, values: Map<String, String>): String {
        val instructions = recipe.optString("instructions")
        val declared = declaredParams(recipe)
        return TOKEN_RE.replace(instructions) { match ->
            val name = match.groupValues[1].trim()
            val param = declared[name]
            if (param == null) {
                match.value
            } else {
                values[name] ?: defaultOf(param) ?: ""
            }
        }
    }

    /**
     * Whether the recipe's success check passes against
     * [probeText] (the note's or page's text, fetched by
     * the caller; null when the probe itself failed).
     * "always" passes; the contains kinds need the text
     * present and the declared pattern found in it
     * (case-insensitive); a missing pattern never passes.
     */
    fun checkPasses(recipe: JSONObject, probeText: String?): Boolean {
        val check = recipe.optJSONObject("successCheck")
            ?: return true
        return when (check.optString("kind", "always")) {
            "always" -> true
            "note_contains", "page_contains" -> {
                val pattern = check.optString("pattern")
                if (pattern.isEmpty()) {
                    false
                } else {
                    probeText != null &&
                        probeText.contains(pattern, ignoreCase = true)
                }
            }
            else -> false
        }
    }

    /** The names of the built-in recipes, in menu order. */
    fun builtinNames(): List<String> = listOf("morning-brief", "watch-page")

    /**
     * A built-in recipe by name, as a raw JSON object that
     * passes [validate] — or null for an unknown name.
     * Built-ins are ordinary recipes (same shape, same
     * rules), shipped ready to run and ready to copy as
     * the starting point for a user's own.
     */
    fun builtin(name: String): JSONObject? = when (name) {
        "morning-brief" -> JSONObject()
            .put("name", "morning-brief")
            .put("title", "Morning brief")
            .put(
                "instructions",
                "Read today's calendar and the newest mail " +
                    "headlines, then write them into a note " +
                    "named morning-brief.txt. End the note " +
                    "with a line \"Today, in short:\" followed " +
                    "by one plain sentence summing up the day."
            )
            .put("params", JSONArray())
            .put(
                "tools",
                JSONArray()
                    .put("calendar_read")
                    .put("gmail_list")
                    .put("gmail_search")
                    .put("write_note")
                    .put("finish")
            )
            .put(
                "successCheck",
                JSONObject()
                    .put("kind", "note_contains")
                    .put("target", "morning-brief.txt")
                    .put("pattern", "Today")
            )
            .put("maxRetries", 1)
            .put("schedule", "daily 07:00")

        "watch-page" -> JSONObject()
            .put("name", "watch-page")
            .put("title", "Watch a page")
            .put(
                "instructions",
                "Open {{url}} and read the page. Write a " +
                    "note named watch-result.txt that starts " +
                    "with \"watch:\" and says whether " +
                    "{{pattern}} appears on the page, quoting " +
                    "the surrounding words when it does."
            )
            .put(
                "params",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("name", "url")
                            .put("type", "string")
                    )
                    .put(
                        JSONObject()
                            .put("name", "pattern")
                            .put("type", "string")
                    )
            )
            .put(
                "tools",
                JSONArray()
                    .put("browser_open")
                    .put("browser_read")
                    .put("write_note")
                    .put("finish")
            )
            .put(
                "successCheck",
                JSONObject()
                    .put("kind", "note_contains")
                    .put("target", "watch-result.txt")
                    .put("pattern", "watch:")
            )
            .put("maxRetries", 1)
            .put("schedule", JSONObject.NULL)

        else -> null
    }
}
