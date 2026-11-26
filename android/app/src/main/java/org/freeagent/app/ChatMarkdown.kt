package org.freeagent.app

/**
 * Chat markdown (v0.12.3) — the pure renderer behind the chat
 * bubbles, in the same style as BrainPacing / ChatData: no
 * Android, no I/O, pinned by JVM tests. MainActivity turns the
 * [Rendered] result into a Spannable; the parsing decisions all
 * live here.
 *
 * Why: chat bubbles used to show the model's raw markdown as
 * literal text — `**`, `##`, backticks and `-` bullets as
 * characters (reported by Ethuram from his phone, with the
 * Agentware screenshots). This renders the subset the models
 * actually emit:
 *
 * - `**bold**`, `*italic*`, `***bold italic***`
 * - `#` … `######` headings (marker gone, [Span.level] kept)
 * - `-` / `*` / `+` bullets, shown with a `•` glyph
 * - `1.` / `1)` numbered lists, number kept
 * - `` `inline code` `` and ``` fenced code blocks (contents
 *   literal — markers inside code are never re-parsed)
 * - paragraphs and line breaks preserved
 *
 * Anything outside the subset — an unmatched `**`, a `#` with
 * no space — is left literal rather than swallowed. The output
 * [Rendered.text] is the full readable text with the covered
 * markers removed, so a long-press copy of a rendered bubble
 * yields clean text, and a message with no markdown renders
 * byte-identical to its input.
 */
object ChatMarkdown {

    enum class Style { BOLD, ITALIC, CODE, CODE_BLOCK, HEADING }

    /** A styled run of [Rendered.text]; [level] is the heading
     *  level (1–6) for [Style.HEADING], 0 otherwise. */
    data class Span(val start: Int, val end: Int, val style: Style, val level: Int = 0)

    data class Rendered(val text: String, val spans: List<Span>)

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s*```.*$")

    fun render(input: String): Rendered {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var inFence = false
        var fenceStart = -1

        fun separator() {
            if (out.isNotEmpty()) out.append('\n')
        }

        fun closeFence() {
            if (fenceStart in 0 until out.length) {
                spans.add(Span(fenceStart, out.length, Style.CODE_BLOCK))
            } else if (fenceStart == out.length && fenceStart > 0 &&
                out[fenceStart - 1] == '\n'
            ) {
                // Empty code block: drop the separator newline
                // added when the fence opened, so no stray blank
                // line is left behind.
                out.deleteCharAt(fenceStart - 1)
            }
            inFence = false
            fenceStart = -1
        }

        for (raw in lines) {
            if (FENCE.matches(raw)) {
                if (inFence) {
                    closeFence()
                } else {
                    separator()
                    inFence = true
                    fenceStart = out.length
                }
                continue
            }
            if (inFence) {
                // Content lines join with '\n', but the first one
                // starts exactly at fenceStart (the separator was
                // already added when the fence opened).
                if (out.length > fenceStart) out.append('\n')
                out.append(raw)
                continue
            }
            val heading = HEADING.matchEntire(raw)
            val bullet = if (heading == null) BULLET.matchEntire(raw) else null
            val numbered =
                if (heading == null && bullet == null) NUMBERED.matchEntire(raw) else null
            when {
                heading != null -> {
                    separator()
                    val start = out.length
                    parseInline(heading.groupValues[2], out, spans)
                    if (out.length > start) {
                        spans.add(
                            Span(start, out.length, Style.HEADING, heading.groupValues[1].length)
                        )
                    }
                }
                bullet != null -> {
                    separator()
                    out.append("• ")
                    parseInline(bullet.groupValues[1], out, spans)
                }
                numbered != null -> {
                    separator()
                    out.append(numbered.groupValues[1]).append(". ")
                    parseInline(numbered.groupValues[2], out, spans)
                }
                else -> {
                    separator()
                    parseInline(raw, out, spans)
                }
            }
        }
        if (inFence) closeFence()
        return Rendered(out.toString(), spans)
    }

    /** Inline constructs within one line (or one recursively
     *  delimited run). Code spans are literal; bold/italic
     *  contents are parsed recursively so `**a *b* c**` works.
     *  An opener with no closer is emitted literally. */
    private fun parseInline(text: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text[i] == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1) {
                        val start = out.length
                        out.append(text, i + 1, close)
                        spans.add(Span(start, out.length, Style.CODE))
                        i = close + 1
                    } else {
                        out.append('`')
                        i++
                    }
                }
                text.startsWith("***", i) -> {
                    val close = text.indexOf("***", i + 3)
                    if (close > i + 3) {
                        val start = out.length
                        parseInline(text.substring(i + 3, close), out, spans)
                        spans.add(Span(start, out.length, Style.BOLD))
                        spans.add(Span(start, out.length, Style.ITALIC))
                        i = close + 3
                    } else {
                        out.append('*')
                        i++
                    }
                }
                text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close > i + 2) {
                        val start = out.length
                        parseInline(text.substring(i + 2, close), out, spans)
                        spans.add(Span(start, out.length, Style.BOLD))
                        i = close + 2
                    } else {
                        out.append('*')
                        i++
                    }
                }
                text[i] == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close > i + 1) {
                        val start = out.length
                        parseInline(text.substring(i + 1, close), out, spans)
                        spans.add(Span(start, out.length, Style.ITALIC))
                        i = close + 1
                    } else {
                        out.append('*')
                        i++
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
    }
}
