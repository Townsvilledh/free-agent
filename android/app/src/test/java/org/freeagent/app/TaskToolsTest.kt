package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.3: tool availability for general tasks — the
 * classes a job's own words require, the plan stamped on
 * the record, the general runner's tool dispatch (driven
 * end-to-end against a fake browser driver), and the
 * progress-aware step budget. The Agentware-shaped
 * fixture is the job whose honest "tools are not
 * available" report exposed the gap.
 */
class TaskToolsTest {

    private val agentwareJob =
        "Pre-kickoff prep for the Agentware hackathon: join " +
            "the Hackathon Raptors Discord server, read my " +
            "Gmail inbox for the organiser's email with the " +
            "perk codes, connect the GitHub repository for " +
            "the entry, and record a demo video of the result."

    // ---------- detection ----------

    @Test
    fun agentwareJobRequiresAllFourClasses() {
        val plan = TaskTools.planFor(agentwareJob)
        assertEquals(
            setOf(
                TaskTools.ToolClass.BROWSER,
                TaskTools.ToolClass.MAIL,
                TaskTools.ToolClass.REPO_CODE,
                TaskTools.ToolClass.VIDEO
            ),
            plan.requires
        )
        assertEquals(
            setOf(
                TaskTools.ToolClass.MAIL,
                TaskTools.ToolClass.REPO_CODE,
                TaskTools.ToolClass.VIDEO
            ),
            plan.unavailable
        )
        assertTrue(plan.partial)
        assertTrue(plan.requiresBrowser)
        // The job's own words authorise the browser
        // allowlist — and mail is NOT smuggled into it
        // (webmail-as-workaround is out of scope).
        assertTrue(plan.browserDomains.contains("discord.com"))
        assertTrue(plan.browserDomains.contains("github.com"))
        assertFalse(plan.browserDomains.contains("gmail.com"))
        assertFalse(plan.browserDomains.contains("mail.google.com"))
    }

    @Test
    fun mailShapedJobDeclaresMailOnly() {
        val plan = TaskTools.planFor(
            "Read my Gmail inbox and summarise today's email"
        )
        assertEquals(setOf(TaskTools.ToolClass.MAIL), plan.requires)
        assertEquals(setOf(TaskTools.ToolClass.MAIL), plan.unavailable)
        assertFalse(plan.requiresBrowser)
        assertTrue(plan.browserDomains.isEmpty())
        assertTrue(plan.partial)
    }

    @Test
    fun browserShapedJobRequiresTheBrowserAndItsDomains() {
        val plan = TaskTools.planFor(
            "Open https://lu.ma/agentware and register for the event"
        )
        assertEquals(setOf(TaskTools.ToolClass.BROWSER), plan.requires)
        assertTrue(plan.unavailable.isEmpty())
        assertFalse(plan.partial)
        assertTrue(plan.browserDomains.contains("lu.ma"))
    }

    @Test
    fun plainNoteJobRequiresNothing() {
        val plan = TaskTools.planFor(
            "Write a note summarising the three ideas we discussed"
        )
        assertTrue(plan.requires.isEmpty())
        assertTrue(plan.unavailable.isEmpty())
        assertFalse(plan.partial)
    }

    @Test
    fun chatIntakeDirectionIsNotDetectionInput() {
        // The intake marker's standing text mentions
        // nothing detectable, but the strip itself is
        // pinned: words after the marker never widen a plan.
        val plan = TaskTools.planFor(
            "Write a note about the meeting\n\n[Chat intake: " +
                "record a demo video and check my gmail]"
        )
        assertTrue(plan.requires.isEmpty())
    }

    @Test
    fun exportGateStaysOnTheOriginalCodeHints() {
        // A pure code job still trips the v0.4 gate…
        assertTrue(TaskTools.looksLikeCodeTask("Write a python script that runs nightly"))
        // …while repository talk alone does not (it is a
        // declaration class, not an export trigger).
        assertFalse(TaskTools.looksLikeCodeTask("Look at the GitHub repository"))
        assertTrue(
            TaskTools.planFor("Look at the GitHub repository")
                .requires.contains(TaskTools.ToolClass.REPO_CODE)
        )
    }

    @Test
    fun packagedRecordsAreNeverPlanned() {
        val packaged = JSONObject()
            .put("kind", "browser")
            .put("text", "Create the account; check the email mailbox")
        assertTrue(TaskTools.isPackaged(packaged))
        assertFalse(TaskTools.isPackaged(JSONObject().put("text", "a job")))
    }

    // ---------- the stamped plan ----------

    @Test
    fun stampPlanRecordsTheDeclarationAndRoundTrips() {
        val record = JSONObject().put("text", agentwareJob)
        val plan = TaskTools.planFor(agentwareJob)
        TaskTools.stampPlan(record, plan)
        assertTrue(record.optBoolean(TaskTools.BROWSER_TOOLS_FIELD))
        val back = TaskTools.planFromRecord(record)!!
        assertEquals(plan.requires, back.requires)
        assertEquals(plan.unavailable, back.unavailable)
        assertEquals(plan.browserDomains, back.browserDomains)
        // Enum order on the record — evidence reads the
        // same on every device.
        assertEquals(
            "BROWSER", record.getJSONArray(TaskTools.REQUIRES_FIELD).getString(0)
        )
    }

    @Test
    fun stampPlanNeverNarrowsAnExistingAllowlist() {
        val record = JSONObject()
            .put("browserDomains", org.json.JSONArray().put("x.com"))
        TaskTools.stampPlan(record, TaskTools.planFor(agentwareJob))
        val domains = record.getJSONArray("browserDomains")
        assertEquals(1, domains.length())
        assertEquals("x.com", domains.getString(0))
    }

    @Test
    fun declarationNamesWhatSheHasAndWhatNeedsTheComputerLane() {
        val record = JSONObject().put("text", agentwareJob)
        TaskTools.stampPlan(record, TaskTools.planFor(agentwareJob))
        val declaration = TaskTransparency.declarationFor(record)!!
        assertTrue(declaration.contains("On this phone she has the browser"))
        assertTrue(declaration.contains("email work"))
        assertTrue(declaration.contains("code and repository work"))
        assertTrue(declaration.contains("video work"))
        assertTrue(declaration.contains("needs the computer lane"))
        assertTrue(declaration.contains("mark the rest in her report"))
        // The 1.0.2 negative pin, kept: no route mechanics
        // on any surface — the declaration included.
        assertFalse(NarrationGuard.containsNarration(declaration))
    }

    @Test
    fun noDeclarationWhenNothingIsMissing() {
        val record = JSONObject().put("text", "Summarise the page")
        TaskTools.stampPlan(
            record,
            TaskTools.planFor("Open https://lu.ma/x and summarise the page")
        )
        assertEquals(null, TaskTransparency.declarationFor(record))
        // And an unplanned (packaged/legacy) record declares
        // nothing either.
        assertEquals(null, TaskTransparency.declarationFor(JSONObject()))
    }

    @Test
    fun environmentNoteTeachesTheRealToolset() {
        val note = TaskTools.environmentNote(TaskTools.planFor(agentwareJob))
        assertTrue(note.contains("browser_open"))
        assertTrue(note.contains("discord.com"))
        assertTrue(note.contains("computer"))
        assertTrue(note.contains("Never claim to have done a part"))
        val plain = TaskTools.environmentNote(
            TaskTools.planFor("Write a note about the meeting")
        )
        assertFalse(plain.contains("browser_open"))
        assertFalse(plain.contains("computer"))
    }

    // ---------- dispatch: a general task drives the
    // browser end-to-end (fake driver) ----------

    /** A fake device browser: stands in for BrowserSession
     *  behind the same Sentinel gate the real executor
     *  applies (BrowserPerception.domainAllowed over the
     *  record's allowlist, same refusal shape). */
    private class FakeBrowser(private val domains: List<String>) {
        val opened = mutableListOf<String>()
        var currentUrl = ""

        fun open(url: String): String {
            if (!BrowserPerception.domainAllowed(url, domains)) {
                return "error: domain " +
                    BrowserPerception.domainOf(url) +
                    " is not in this task's browser allowlist"
            }
            opened.add(url)
            currentUrl = url
            return "opened $url — page state follows"
        }

        fun read(): String = "page at $currentUrl with 3 elements"
    }

    @Test
    fun generalTaskDrivesTheBrowserWithinItsAllowlist() {
        val plan = TaskTools.planFor(
            "Open https://lu.ma/agentware and register for the event"
        )
        val record = JSONObject()
        TaskTools.stampPlan(record, plan)
        // TaskRunner.isBrowserTask's inputs, from the plan:
        assertTrue(record.optBoolean(TaskTools.BROWSER_TOOLS_FIELD))

        val browser = FakeBrowser(plan.browserDomains)
        val observations = mutableListOf<String>()
        // The model's scripted calls, through the runner's
        // own dispatch rule (TaskTools.executorFor).
        val script = listOf(
            "browser_open" to "https://lu.ma/agentware",
            "browser_read" to "",
            "browser_open" to "https://evil.example.com/phish",
            "x_post" to "hello",
            "write_note" to "progress"
        )
        for ((tool, arg) in script) {
            val observation = when (
                TaskTools.executorFor(true, tool)
            ) {
                TaskTools.Executor.BROWSER -> when (tool) {
                    "browser_open" -> browser.open(arg)
                    else -> browser.read()
                }
                TaskTools.Executor.REFUSE_X -> TaskTools.X_REFUSAL
                TaskTools.Executor.NOTES -> "note written"
                TaskTools.Executor.UNKNOWN -> "error: unknown tool"
            }
            observations.add(observation)
        }
        // The allowed page opened and read back…
        assertEquals(listOf("https://lu.ma/agentware"), browser.opened)
        assertTrue(observations[0].startsWith("opened"))
        assertTrue(observations[1].contains("lu.ma"))
        // …the unnamed domain was refused by the Sentinel
        // gate, never opened…
        assertTrue(observations[2].startsWith("error:"))
        assertTrue(observations[2].contains("allowlist"))
        // …the X lane tool was refused by dispatch, not
        // routed to the browser executor…
        assertTrue(observations[3].contains("SENTINEL"))
        // …and notes stayed with the note executor.
        assertEquals("note written", observations[4])
    }

    @Test
    fun dispatchWithoutBrowserActivationRefusesBrowserFamily() {
        assertEquals(
            TaskTools.Executor.UNKNOWN,
            TaskTools.executorFor(false, "browser_open")
        )
        assertEquals(
            TaskTools.Executor.REFUSE_X,
            TaskTools.executorFor(false, "x_read_feed")
        )
        assertEquals(
            TaskTools.Executor.NOTES,
            TaskTools.executorFor(false, "web_search")
        )
        assertEquals(
            TaskTools.Executor.UNKNOWN,
            TaskTools.executorFor(true, "gmail_send")
        )
    }

    @Test
    fun browserStepsRenderWithSiteNames() {
        // The transparency step list names browser steps by
        // site (already supported) — pinned here for a
        // GENERAL task's record shape (TaskRunner's real
        // message shapes, as in TaskTransparencyTest).
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "user").put(
                "content", "Open https://lu.ma/agentware"
            ))
            .put(JSONObject().put("role", "assistant").put(
                "content",
                "```json\n{\"tool\": \"browser_open\", \"args\": " +
                    "{\"url\": \"https://lu.ma/agentware\"}}\n```"
            ))
            .put(JSONObject().put("role", "user").put(
                "content",
                "Tool result (browser_open): BROWSER_OK opened " +
                    "https://lu.ma/agentware\nURL: " +
                    "https://lu.ma/agentware\nTitle: Agentware"
            ))
        val record = JSONObject()
            .put("id", "task-1")
            .put("text", "Open https://lu.ma/agentware")
            .put("state", TaskStates.RUNNING)
            .put("messages", messages)
        val steps = TaskTransparency.stepsFor(record)
        assertEquals(1, steps.size)
        assertEquals("Opened lu.ma", steps[0].name)
    }

    // ---------- the progress-aware budget ----------

    @Test
    fun progressingWindowRestsAndResumesItself() {
        assertEquals(
            StepBudget.Decision.AUTO_REST,
            StepBudget.decide(30, 5, 0, 0)
        )
        assertEquals(
            StepBudget.Decision.AUTO_REST,
            StepBudget.decide(3, 2, 1, 5)
        )
    }

    @Test
    fun stalledWindowParksForTheUser() {
        // Errors dominating.
        assertEquals(
            StepBudget.Decision.PARK,
            StepBudget.decide(5, 30, 0, 0)
        )
        // A failure streak running, even with successes.
        assertEquals(
            StepBudget.Decision.PARK,
            StepBudget.decide(10, 2, 3, 0)
        )
        // Nothing ever landed — nudges are not progress.
        assertEquals(
            StepBudget.Decision.PARK,
            StepBudget.decide(0, 0, 0, 0)
        )
        assertEquals(
            StepBudget.Decision.PARK,
            StepBudget.decide(0, 8, 0, 0)
        )
    }

    @Test
    fun dailyRestCapParksEvenAProgressingTask() {
        assertEquals(
            StepBudget.Decision.PARK,
            StepBudget.decide(
                30, 5, 0, StepBudget.MAX_AUTO_RESTS_PER_DAY
            )
        )
    }

    @Test
    fun legacyWindowDerivesFromLifetimeSteps() {
        val mid = JSONObject().put("steps", 37)
        assertEquals(37, StepBudget.windowFor(mid, 40))
        val spent = JSONObject().put("steps", 40)
        assertEquals(0, StepBudget.windowFor(spent, 40))
        val recorded = JSONObject()
            .put("steps", 77)
            .put(StepBudget.WINDOW_FIELD, 12)
        assertEquals(12, StepBudget.windowFor(recorded, 40))
    }

    @Test
    fun restsCountPerDayOnly() {
        val today = StepBudget.dayKey(System.currentTimeMillis())
        val record = JSONObject()
            .put(StepBudget.REST_DAY_FIELD, today)
            .put(StepBudget.RESTS_FIELD, 3)
        assertEquals(3, StepBudget.restsToday(record, today))
        assertEquals(0, StepBudget.restsToday(record, "1999-01-01"))
    }

    @Test
    fun budgetRestSurfacesSayRestingNotRouteMechanics() {
        val now = System.currentTimeMillis()
        val record = JSONObject()
            .put("id", "task-1")
            .put("text", "A long job")
            .put("state", TaskStates.WAITING_ROUTE)
            .put("steps", 40)
            .put("note", StepBudget.REST_NOTE)
            .put("nextRetryAt", now + 60_000L)
            .put("createdAt", now - 600_000L)
            .put("updatedAt", now)
            .put(StepBudget.REST_FLAG, true)
        val status = TaskTransparency.statusText(record, now)
        assertTrue(status.contains("Resting between work sessions"))
        assertTrue(status.contains("continues on her own"))
        assertFalse(NarrationGuard.containsNarration(status))
        val expanded = TaskTransparency.expandedText(record, now)
        assertTrue(expanded.contains("Resting between work sessions"))
        // The rest note itself is narration-clean.
        assertFalse(NarrationGuard.containsNarration(StepBudget.REST_NOTE))
    }

    @Test
    fun parkedBudgetKeepsTheSayContinueShape() {
        val now = System.currentTimeMillis()
        val record = JSONObject()
            .put("id", "task-1")
            .put("text", "A long job")
            .put("state", TaskStates.PAUSED)
            .put("steps", 40)
            .put(
                "note",
                "Step budget (40) reached — resume to continue."
            )
            .put("createdAt", now - 600_000L)
            .put("updatedAt", now)
        val status = TaskTransparency.statusText(record, now)
        assertTrue(status.contains("resume to continue"))
    }
}
