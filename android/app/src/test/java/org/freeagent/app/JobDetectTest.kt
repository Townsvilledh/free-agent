package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.13: chat→task detection, pinned over a fixture set of
 *  job-shaped, ambiguous and chat-shaped messages. The rules
 *  are conservative on purpose: a missed job stays a chat
 *  answer, a false task spends the engine on conversation. */
class JobDetectTest {

    private fun classify(text: String): JobDetect.Outcome =
        JobDetect.classify(text, emptyList())

    private fun assertJob(text: String) {
        val out = classify(text)
        assertTrue(
            "expected StartTask for: $text — got $out",
            out is JobDetect.Outcome.StartTask
        )
        assertEquals(
            text.trim(), (out as JobDetect.Outcome.StartTask).instruction
        )
    }

    private fun assertChat(text: String) {
        val out = classify(text)
        assertEquals(
            "expected NotJob for: $text — got $out",
            JobDetect.Outcome.NotJob, out
        )
    }

    // ---------- job-shaped → StartTask ----------

    @Test
    fun jobFixturesStartTasks() {
        val jobs = listOf(
            "Create my X account",
            "Post this to X: the 0.13 release is live",
            "Sign up for the Agentware hackathon for me",
            "Join the Hackathon Raptors Discord and grab the perk codes",
            "Check my Gmail for the organiser email and tell me what it says",
            "Watch the registry and let me know when it passes 50 names",
            "Every morning, check the new markets and tell me if one opened",
            "Go to agentwarehack.com and find the submission deadline",
            "Register me on Devpost for the buildathon",
            "Send an email to the landlord about the December rent",
            "Take this on as a task: build the demo and submit it",
            "Can you post the walkthrough video to my X account?",
            "Download the Trap Pack from Discord when it drops",
            "Open an account on LaborX with my Gmail"
        )
        jobs.forEach { assertJob(it) }
    }

    @Test
    fun longBriefStartsTask() {
        val brief = "Build a new agent for the hackathon. " +
            "It must read an inbox, classify each message, book " +
            "meetings on the calendar, and write a digest at the " +
            "end of every run. Safety is enforced in code: an " +
            "injection firewall, a policy gate before every send, " +
            "a kill switch, and a hash-chained audit trace that a " +
            "judge can replay. Ship a sandbox mode with fixture " +
            "data so anyone can run it in fifteen minutes with no " +
            "account. Create the public repo at kickoff, MIT " +
            "licence, no secrets committed. Record a three minute " +
            "demo video of one full unattended run and submit the " +
            "form before the freeze on Monday at 18:00 UTC. Report " +
            "to me every six hours while you work, and immediately " +
            "when the repo exists, when the first run passes, and " +
            "when the submission is in. Never fake a result."
        assertTrue(brief.length >= 600)
        assertJob(brief)
    }

    // ---------- chat-shaped → NotJob ----------

    @Test
    fun chatFixturesStayChat() {
        val chats = listOf(
            "What is the capital of France?",
            "How does WalletConnect pairing actually work?",
            "Can you explain what a policy gate is?",
            "Why did my swap fail yesterday?",
            "Write a haiku about the Lagos rain",
            "Draft a tweet announcing the 0.12.3 release",
            "Translate 'good morning' into Yoruba",
            "Tell me a joke",
            "What do you think about Bitcoin today?",
            "How much is 15% of 2400?",
            "Thanks, that worked!",
            "ok",
            "Summarise this for me: the meeting moved to Friday",
            "Who won the match last night?",
            "Should I sell my SUI?",
            "Give me an example of a hash chain"
        )
        chats.forEach { assertChat(it) }
    }

    // ---------- ambiguous → taken on (v1.0.7) ----------

    /** v1.0.7: the ambiguous band no longer asks the
     *  confirmation question — job-shaped input is taken
     *  on automatically (chat-is-the-engine). These
     *  fixtures expected AskQuestion up to 1.0.6; the
     *  contract changed deliberately and the pins moved
     *  with it. */
    @Test
    fun ambiguousFixturesStartTasks() {
        val jobs = listOf(
            "Find me a cheap flight to Abuja next week",
            "Check the price of gold right now",
            "Create an account for me",
            "Book a table for two on Friday night",
            "Research the best no-KYC freelance platforms",
            "Find three hackathons I can enter this week",
            // The 2026-11-27 screenshot's exact message:
            // a plain job that got the retired question
            // while her X lane was already running.
            "create an email for yourself"
        )
        jobs.forEach { assertJob(it) }
    }

    @Test
    fun mediumBriefStarts() {
        val brief = "Prepare the Stellar buildathon entry: the " +
            "repo, the demo script, the pitch deck outline and " +
            "the submission checklist, all before the deadline " +
            "on the first of December, and keep me posted as " +
            "each piece lands so I can review them in order."
        assertTrue(brief.length in 200..599)
        assertJob(brief)
    }

    // ---------- fluency intake: links, fragments, files ----------

    @Test
    fun bareLinkStartsTaskWithInferredInstruction() {
        val url = "https://dorahacks.io/hackathon/agentware/detail"
        val out = JobDetect.classify(url, emptyList())
        assertTrue(out is JobDetect.Outcome.StartTask)
        val ins = (out as JobDetect.Outcome.StartTask).instruction
        assertTrue(ins.contains(url))
        assertTrue(ins.contains("Fetch it"))
    }

    @Test
    fun linkPlusFragmentStartsTask() {
        val msg = "https://agentwarehack.com/rules this one"
        val out = JobDetect.classify(msg, emptyList())
        assertTrue(out is JobDetect.Outcome.StartTask)
    }

    @Test
    fun linkWithAQuestionStaysChat() {
        assertChat("https://example.com/page what is this site?")
    }

    @Test
    fun fileWithNoWordsStartsTask() {
        val out = JobDetect.classify(
            "", emptyList(), hasAttachment = true
        )
        assertTrue(out is JobDetect.Outcome.StartTask)
    }

    @Test
    fun fileWithFragmentStartsTask() {
        for (frag in listOf("fix this", "this one", "check this")) {
            val out = JobDetect.classify(
                frag, emptyList(), hasAttachment = true
            )
            assertTrue(
                "fragment '$frag' with a file should start a task",
                out is JobDetect.Outcome.StartTask
            )
        }
    }

    @Test
    fun fileWithAQuestionStaysChat() {
        val out = JobDetect.classify(
            "what is in this picture?", emptyList(),
            hasAttachment = true
        )
        assertEquals(JobDetect.Outcome.NotJob, out)
    }

    @Test
    fun fileWithThanksStaysChat() {
        val out = JobDetect.classify(
            "thank you", emptyList(), hasAttachment = true
        )
        assertEquals(JobDetect.Outcome.NotJob, out)
    }

    @Test
    fun fragmentWithoutFileIsNotATask() {
        // The same words with nothing sent are conversation —
        // the file is what makes them a hand-over.
        assertChat("fix this")
    }

    // ---------- the pending question ----------

    private fun thread(vararg pairs: Pair<String, String>): List<JSONObject> =
        pairs.map {
            JSONObject().put("role", it.first).put("content", it.second)
        }

    @Test
    fun pendingInstructionFoundBehindQuestion() {
        val history = thread(
            "user" to "Find me a cheap flight to Abuja next week",
            "assistant" to JobDetect.QUESTION,
            "user" to "yes"
        )
        assertEquals(
            "Find me a cheap flight to Abuja next week",
            JobDetect.pendingInstruction(history)
        )
    }

    @Test
    fun pendingInstructionNeedsTheQuestion() {
        val history = thread(
            "user" to "Find me a cheap flight to Abuja next week",
            "assistant" to "Here are some options I remember.",
            "user" to "yes"
        )
        assertNull(JobDetect.pendingInstruction(history))
    }

    @Test
    fun yesAfterQuestionStartsThePendingJob() {
        val history = thread(
            "user" to "Find me a cheap flight to Abuja next week",
            "assistant" to JobDetect.QUESTION,
            "user" to "yes"
        )
        val out = JobDetect.classify("yes", history)
        assertTrue(out is JobDetect.Outcome.StartTask)
        assertEquals(
            "Find me a cheap flight to Abuja next week",
            (out as JobDetect.Outcome.StartTask).instruction
        )
    }

    @Test
    fun yesAfterModelAskedQuestionAlsoResolves() {
        // The model path: its reply ENDS with the question
        // (a sentence of its own in front) — same resolution.
        val history = thread(
            "user" to "Sort out the Discord perk codes",
            "assistant" to "That needs my browser and your " +
                "accounts, so I can't just answer it here. " +
                JobDetect.QUESTION,
            "user" to "go ahead"
        )
        val out = JobDetect.classify("go ahead", history)
        assertTrue(out is JobDetect.Outcome.StartTask)
    }

    @Test
    fun noAfterQuestionStaysChat() {
        val history = thread(
            "user" to "Find me a cheap flight to Abuja next week",
            "assistant" to JobDetect.QUESTION,
            "user" to "no"
        )
        assertEquals(
            JobDetect.Outcome.NotJob, JobDetect.classify("no", history)
        )
    }

    @Test
    fun bareYesWithoutPendingIsChat() {
        assertChat("yes")
    }

    @Test
    fun longYesCarryingContentIsNotABareYes() {
        assertFalse(
            JobDetect.isAffirmative(
                "yes and while you are at it here is the whole " +
                    "story of what happened last week in detail"
            )
        )
    }

    @Test
    fun affirmativeAndNegativeShapes() {
        assertTrue(JobDetect.isAffirmative("Yes"))
        assertTrue(JobDetect.isAffirmative("yeah"))
        assertTrue(JobDetect.isAffirmative("go ahead"))
        assertTrue(JobDetect.isAffirmative("yes please"))
        assertFalse(JobDetect.isAffirmative("yesterday was fine"))
        assertTrue(JobDetect.isNegative("no"))
        assertTrue(JobDetect.isNegative("not now"))
        assertTrue(JobDetect.isNegative("never mind"))
        assertFalse(JobDetect.isNegative("noodles are great"))
    }
}
