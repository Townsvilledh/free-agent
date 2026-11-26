package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.12.3: chat bubbles render the model's markdown instead
 *  of showing the raw markers (`**`, `##`, backticks) as
 *  literal characters — the defect in Ethuram's Agentware
 *  screenshots. ChatMarkdown is the pure renderer; these tests
 *  pin the covered subset: markers gone from the output text,
 *  spans where the styles belong, code contents literal, and a
 *  plain message rendered byte-identical. */
class ChatMarkdownTest {

    private fun spansOf(
        r: ChatMarkdown.Rendered,
        style: ChatMarkdown.Style
    ): List<ChatMarkdown.Span> = r.spans.filter { it.style == style }

    private fun spanText(r: ChatMarkdown.Rendered, s: ChatMarkdown.Span): String =
        r.text.substring(s.start, s.end)

    @Test
    fun plainMessageRendersUnchanged() {
        val input = "Hello — the run is at 4:53, battery 76%.\nSecond line here."
        val r = ChatMarkdown.render(input)
        assertEquals(input, r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun emptyMessageRendersEmpty() {
        val r = ChatMarkdown.render("")
        assertEquals("", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun boldMarkersRemovedAndSpanPlaced() {
        val r = ChatMarkdown.render("Got it — **starting now** as ordered.")
        assertEquals("Got it — starting now as ordered.", r.text)
        val bold = spansOf(r, ChatMarkdown.Style.BOLD)
        assertEquals(1, bold.size)
        assertEquals("starting now", spanText(r, bold[0]))
    }

    @Test
    fun italicMarkersRemovedAndSpanPlaced() {
        val r = ChatMarkdown.render("a *small* note")
        assertEquals("a small note", r.text)
        val italic = spansOf(r, ChatMarkdown.Style.ITALIC)
        assertEquals(1, italic.size)
        assertEquals("small", spanText(r, italic[0]))
    }

    @Test
    fun boldItalicTripleStarGetsBothStyles() {
        val r = ChatMarkdown.render("***both***")
        assertEquals("both", r.text)
        assertEquals(1, spansOf(r, ChatMarkdown.Style.BOLD).size)
        assertEquals(1, spansOf(r, ChatMarkdown.Style.ITALIC).size)
    }

    @Test
    fun nestedItalicInsideBold() {
        val r = ChatMarkdown.render("**a *b* c**")
        assertEquals("a b c", r.text)
        val bold = spansOf(r, ChatMarkdown.Style.BOLD)
        assertEquals(1, bold.size)
        assertEquals("a b c", spanText(r, bold[0]))
        val italic = spansOf(r, ChatMarkdown.Style.ITALIC)
        assertEquals(1, italic.size)
        assertEquals("b", spanText(r, italic[0]))
    }

    @Test
    fun headingsLoseTheirHashesAndKeepLevel() {
        val r = ChatMarkdown.render("# Top\n## Middle\n### Deep")
        assertEquals("Top\nMiddle\nDeep", r.text)
        val headings = spansOf(r, ChatMarkdown.Style.HEADING)
        assertEquals(3, headings.size)
        assertEquals(listOf(1, 2, 3), headings.map { it.level })
        assertEquals("Middle", spanText(r, headings[1]))
    }

    @Test
    fun bulletsBecomeGlyphBullets() {
        val r = ChatMarkdown.render("- one\n- two\n* three")
        assertEquals("• one\n• two\n• three", r.text)
    }

    @Test
    fun numberedListKeepsItsNumbers() {
        val r = ChatMarkdown.render("1. **Trigger** – a new email\n2. Classify")
        assertEquals("1. Trigger – a new email\n2. Classify", r.text)
        val bold = spansOf(r, ChatMarkdown.Style.BOLD)
        assertEquals(1, bold.size)
        assertEquals("Trigger", spanText(r, bold[0]))
    }

    @Test
    fun inlineCodeIsMonospaceSpanAndLiteral() {
        val r = ChatMarkdown.render("run `ls -la` first")
        assertEquals("run ls -la first", r.text)
        val code = spansOf(r, ChatMarkdown.Style.CODE)
        assertEquals(1, code.size)
        assertEquals("ls -la", spanText(r, code[0]))
    }

    @Test
    fun codeBlockContentsAreNeverReparsed() {
        val r = ChatMarkdown.render("```\nval x = **not bold**\n# not a heading\n```")
        assertEquals("val x = **not bold**\n# not a heading", r.text)
        assertEquals(1, spansOf(r, ChatMarkdown.Style.CODE_BLOCK).size)
        assertTrue(spansOf(r, ChatMarkdown.Style.BOLD).isEmpty())
        assertTrue(spansOf(r, ChatMarkdown.Style.HEADING).isEmpty())
        assertFalse(r.text.contains("```"))
    }

    @Test
    fun codeBlockAfterTextKeepsSeparation() {
        val r = ChatMarkdown.render("look:\n```\ncode line\n```\ndone")
        assertEquals("look:\ncode line\ndone", r.text)
        val block = spansOf(r, ChatMarkdown.Style.CODE_BLOCK)
        assertEquals(1, block.size)
        assertEquals("code line", spanText(r, block[0]))
    }

    @Test
    fun unclosedFenceStillRendersAsCode() {
        val r = ChatMarkdown.render("```\ncode")
        assertEquals("code", r.text)
        assertEquals(1, spansOf(r, ChatMarkdown.Style.CODE_BLOCK).size)
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        val r = ChatMarkdown.render("a ** b and a #tag")
        assertEquals("a ** b and a #tag", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun hashWithoutSpaceIsNotAHeading() {
        val r = ChatMarkdown.render("##NoSpace")
        assertEquals("##NoSpace", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun paragraphsKeepTheirBlankLine() {
        val r = ChatMarkdown.render("first\n\nsecond")
        assertEquals("first\n\nsecond", r.text)
    }

    /** The shape of the actual reply that prompted this fix:
     *  headings, bold lead-ins, bullets, a numbered list — no
     *  raw marker from the covered subset may survive. */
    @Test
    fun agentwareShapedReplyHasNoRawMarkers() {
        val input = "## Nightclerk — Agentware 2026\n" +
            "\n" +
            "### How it works\n" +
            "1. **Trigger** – a new email arrives\n" +
            "2. **Classify** – I read the email\n" +
            "- **Policy check** – before any action\n" +
            "- Audit trail in `trace.jsonl`"
        val r = ChatMarkdown.render(input)
        assertFalse(r.text.contains("**"))
        assertFalse(r.text.contains("##"))
        assertFalse(r.text.contains("`"))
        assertTrue(r.text.contains("• Policy check"))
        assertTrue(r.text.contains("1. Trigger"))
        assertEquals(3, spansOf(r, ChatMarkdown.Style.BOLD).size)
        assertEquals(2, spansOf(r, ChatMarkdown.Style.HEADING).size)
        assertEquals(1, spansOf(r, ChatMarkdown.Style.CODE).size)
    }
}
