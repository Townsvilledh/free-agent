package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserHandoff (v0.10.1): the planned user step — gated on the
 * packaged task's own flag, recognised by its observation
 * prefix, with the human's step text tidied/capped and a
 * plain default when the model said nothing usable.
 */
class UserHandoffTest {

    @Test
    fun handoffHonouredOnlyWhenTheTaskCarriesTheFlag() {
        assertTrue(UserHandoff.mayHandoff(true))
        assertFalse(UserHandoff.mayHandoff(false))
    }

    @Test
    fun observationPrefixIsRecognised() {
        assertTrue(
            UserHandoff.isHandoffObservation(
                "HANDOFF_REQUESTED — the step is recorded."
            )
        )
        assertFalse(UserHandoff.isHandoffObservation("BROWSER_OK opened x"))
        assertFalse(UserHandoff.isHandoffObservation("error: HANDOFF_REQUESTED"))
    }

    @Test
    fun stepTextIsTidiedAndCapped() {
        val messy = "  Log in\n\n  to   X please  "
        assertEquals("Log in to X please", UserHandoff.cleanStep(messy, "x.com"))
        val long = "x".repeat(500)
        assertEquals(300, UserHandoff.cleanStep(long, "x.com").length)
    }

    @Test
    fun blankStepFallsBackToThePlainDefault() {
        val step = UserHandoff.cleanStep("   ", "x.com")
        assertTrue(step.contains("x.com"))
        assertTrue(step.contains("Open Lille's browser"))
        assertTrue(step.contains("hand back to Lille"))
    }

    @Test
    fun wallKindIsDistinctFromChallengeKinds() {
        assertEquals("user_step", UserHandoff.WALL_KIND)
    }

    @Test
    fun resumeNoteNamesVerificationNotAssumption() {
        val note = UserHandoff.resumeNote("x.com")
        assertTrue(note.contains("x.com"))
        assertTrue(note.contains("never saw any password"))
        assertTrue(note.contains("x_session_status"))
    }
}
