package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the device-lane perception + policy layer
 * (v0.9). The fixtures are COLLECT_JS results — the exact JSON
 * the WebView script returns for a page — so ref extraction,
 * SECRET classification, challenge detection and the type guards
 * are all exercised off-device, against the same shapes the
 * host lane's tests use.
 */
class BrowserPerceptionTest {

    /** A signup form as COLLECT_JS sees it: an email input, a
     *  password input (SECRET), a submit button with no label,
     *  and the legal-link trap underneath. */
    private val signupJson = """
        {
          "url": "https://x.com/i/flow/signup",
          "title": "Sign up for X",
          "text": "Create your account Email Password Sign up Terms of Service Privacy Policy",
          "elements": [
            {"ref": 1, "tag": "input", "type": "email", "name": "username_or_email",
             "placeholder": "Email"},
            {"ref": 2, "tag": "input", "type": "password", "name": "password",
             "secret": true},
            {"ref": 3, "tag": "button", "type": "submit"},
            {"ref": 4, "tag": "a", "text": "Terms of Service",
             "href": "https://x.com/tos"}
          ],
          "frames": []
        }
    """.trimIndent()

    @Test
    fun refsAreExtractedInOrder() {
        val state = BrowserPerception.parseCollect(signupJson)
        assertEquals(4, state.elements.size)
        assertEquals(listOf(1, 2, 3, 4), state.elements.map { it.ref })
        assertEquals("email", state.element(1)?.type)
        assertEquals("https://x.com/tos", state.element(4)?.href)
        assertEquals("https://x.com/i/flow/signup", state.url)
    }

    @Test
    fun missingRefsFallBackToPosition() {
        val json = """{"url":"https://example.com","title":"","text":"",
            "elements":[{"tag":"button","text":"Go"}], "frames":[]}"""
        val state = BrowserPerception.parseCollect(json)
        assertEquals(1, state.elements[0].ref)
    }

    @Test
    fun secretFieldClassification() {
        val state = BrowserPerception.parseCollect(signupJson)
        assertTrue(state.element(2)!!.secret)
        assertFalse(state.element(1)!!.secret)
        // The classifier itself (probe parity with browse.mjs):
        assertTrue(BrowserPerception.isSecretField("password", "", ""))
        assertTrue(BrowserPerception.isSecretField("text", "one-time-code", ""))
        assertTrue(BrowserPerception.isSecretField("text", "", "user_pwd"))
        assertFalse(BrowserPerception.isSecretField("email", "", "username_or_email"))
        assertFalse(BrowserPerception.isSecretField("text", "", "email"))
    }

    @Test
    fun plainTypeRefusesSecretField() {
        val state = BrowserPerception.parseCollect(signupJson)
        val refusal = BrowserPerception.plainTypeRefusal(state.element(2))
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("SECRET_FIELD"))
        assertNull(BrowserPerception.plainTypeRefusal(state.element(1)))
        // Unknown element: no snapshot refusal (the live probe decides).
        assertNull(BrowserPerception.plainTypeRefusal(null))
    }

    @Test
    fun secretTypeRefusesPlainField() {
        val state = BrowserPerception.parseCollect(signupJson)
        val refusal = BrowserPerception.secretTypeRefusal(state.element(1))
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("NOT_A_SECRET_FIELD"))
        assertNull(BrowserPerception.secretTypeRefusal(state.element(2)))
        assertNull(BrowserPerception.secretTypeRefusal(null))
    }

    @Test
    fun unlabelledSubmitButtonIsVisibleToTheModel() {
        val state = BrowserPerception.parseCollect(signupJson)
        val out = BrowserPerception.formatState(state, "PAGE")
        assertTrue(out.contains("(submit button)"))
        assertTrue(out.contains("SECRET"))
        assertTrue(out.contains("href=https://x.com/tos"))
    }

    // ---------- challenge detection: presented only ----------

    private fun page(
        url: String = "https://example.com/",
        text: String = "An ordinary page",
        frames: String = "[]"
    ): BrowserPerception.PageState = BrowserPerception.parseCollect(
        """{"url":"$url","title":"t","text":"$text","elements":[],"frames":$frames}"""
    )

    @Test
    fun arkoseUrlIsAChallenge() {
        val ch = BrowserPerception.detectChallenge(
            page(url = "https://client-api.arkose.com/fc/gc/")
        )
        assertNotNull(ch)
        assertEquals("captcha", ch!!.kind)
    }

    @Test
    fun presentedRecaptchaIframeIsAChallenge() {
        val ch = BrowserPerception.detectChallenge(
            page(frames = """[{"src":"https://www.google.com/recaptcha/api2/anchor",
                "w":304,"h":78,"visible":true}]""")
        )
        assertNotNull(ch)
        assertEquals("captcha", ch!!.kind)
    }

    @Test
    fun hiddenScoringIframeIsNotAWall() {
        // reCAPTCHA v3-style passive iframe: 0x0 / invisible on an
        // ordinary page — using the page never touches it.
        val ch = BrowserPerception.detectChallenge(
            page(frames = """[{"src":"https://www.google.com/recaptcha/api.js",
                "w":0,"h":0,"visible":false}]""")
        )
        assertNull(ch)
    }

    @Test
    fun challengeTextIsDetected() {
        val ch = BrowserPerception.detectChallenge(
            page(text = "Verify you are human to continue")
        )
        assertNotNull(ch)
        assertEquals("captcha", ch!!.kind)
        val phone = BrowserPerception.detectChallenge(
            page(text = "Please add a phone number to continue")
        )
        assertEquals("phone", phone!!.kind)
    }

    @Test
    fun ordinaryPageHasNoChallenge() {
        assertNull(BrowserPerception.detectChallenge(page()))
        assertNull(
            BrowserPerception.detectChallenge(
                BrowserPerception.parseCollect(signupJson)
            )
        )
    }

    @Test
    fun challengeFormatsAsStopObservation() {
        val state = page(url = "https://client-api.arkose.com/fc/gc/")
        val out = BrowserPerception.formatState(state, "PAGE")
        assertTrue(out.startsWith("CHALLENGE kind=captcha"))
        assertTrue(out.contains("do NOT"))
    }

    // ---------- allowlist ----------

    @Test
    fun allowlistRules() {
        val domains = listOf("x.com", "maildrop.cc")
        assertTrue(BrowserPerception.domainAllowed("https://x.com/home", domains))
        assertTrue(BrowserPerception.domainAllowed("https://www.x.com/home", domains))
        assertTrue(BrowserPerception.domainAllowed("https://inbox.maildrop.cc/", domains))
        assertFalse(BrowserPerception.domainAllowed("https://notx.com/", domains))
        assertFalse(BrowserPerception.domainAllowed("https://x.com.evil.example/", domains))
        assertFalse(BrowserPerception.domainAllowed("https://x.com/home", emptyList()))
        assertFalse(BrowserPerception.domainAllowed("not a url", domains))
        assertEquals("x.com", BrowserPerception.domainOf("https://X.com/home?x=1"))
    }

    // ---------- X lane parsing ----------

    @Test
    fun handleParsing() {
        assertEquals("LilleAgent", BrowserPerception.parseHandle("/LilleAgent"))
        assertEquals(
            "LilleAgent",
            BrowserPerception.parseHandle("text:Lille @LilleAgent")
        )
        assertNull(BrowserPerception.parseHandle(null))
        assertNull(BrowserPerception.parseHandle(""))
        assertNull(BrowserPerception.parseHandle("text:No handle here"))
    }

    @Test
    fun articlesParsing() {
        val json = """[{"author":"Lille @LilleAgent","text":"Hello world",
            "url":"https://x.com/LilleAgent/status/1"},
            {"author":"A","text":"B","url":""}]"""
        val posts = BrowserPerception.parseArticles(json)
        assertEquals(2, posts.size)
        assertEquals("https://x.com/LilleAgent/status/1", posts[0].url)
        val out = BrowserPerception.formatPosts("X_FEED_OK posts=2", posts)
        assertTrue(out.contains("Hello world"))
        assertTrue(out.contains("[https://x.com/LilleAgent/status/1]"))
    }

    @Test
    fun unquoteJsResultUnwrapsOneLevel() {
        assertEquals("/LilleAgent", BrowserPerception.unquoteJsResult("\"/LilleAgent\""))
        assertNull(BrowserPerception.unquoteJsResult("null"))
        assertNull(BrowserPerception.unquoteJsResult(null))
        assertEquals("{\"a\":1}", BrowserPerception.unquoteJsResult("\"{\\\"a\\\":1}\""))
    }
}
