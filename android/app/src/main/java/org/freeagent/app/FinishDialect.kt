package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * FINISH-DIALECT RECOVERY (v1.0.14) — finish(...) emitted
 * as literal text instead of a tool call, recovered as
 * the call.
 *
 * The 1.0.13 battery's schedule-a / format-a shape: the
 * model's whole reply is a Python-style call —
 * finish("Workshop time conversion: ...", []) or
 * finish({"name": "Adaeze", ...}, []) — which the JSON
 * tool-call parser read as no call at all, so a finished
 * task parked. Spec mode never needed the recovery (it
 * grades the trace); the plain loop and the device
 * runner did. The core mirror is
 * free_agent.loop._parse_finish_call.
 *
 * Anchored at the reply's start: a finish(...) mentioned
 * inside narration is NOT a call and is never recovered.
 * Pure + org.json; pinned by Lille114Test.
 */
object FinishDialect {

    /** The recovered finish args ({summary, outputs}) or
     *  null when the reply is not a bare finish(...) call. */
    fun parse(content: String): JSONObject? {
        val s = content.trim()
        if (!s.startsWith("finish")) return null
        val open = s.indexOf('(')
        if (open < 0 || s.substring(0, open).trim() != "finish") {
            return null
        }
        val close = matchingClose(s, open)
        if (close < 0) return null
        val inner = s.substring(open + 1, close)
        var summary: String? = null
        var outputs = JSONArray()
        for (part in splitArgs(inner)) {
            val kw = Regex("^([A-Za-z_]\\w*)\\s*=\\s*(.*)$",
                RegexOption.DOT_MATCHES_ALL).find(part)
            val key = kw?.groupValues?.get(1)
            val raw = (kw?.groupValues?.get(2) ?: part).trim()
            when {
                key == "outputs" || (key == null && raw.startsWith("[")) -> {
                    try {
                        val arr = JSONArray(raw)
                        val clean = JSONArray()
                        for (i in 0 until arr.length()) {
                            clean.put(arr.optString(i))
                        }
                        outputs = clean
                    } catch (e: Exception) {
                        // A non-array outputs stays empty —
                        // the summary is the deliverable.
                    }
                }
                key == "summary" || (key == null && summary == null) -> {
                    summary = when {
                        raw.startsWith("{") -> {
                            try {
                                val obj = JSONObject(raw)
                                if (obj.has("summary")) {
                                    val o = obj.optJSONArray("outputs")
                                    if (o != null) outputs = o
                                    obj.optString("summary")
                                } else {
                                    // format-a's shape: the
                                    // deliverable itself rode
                                    // in as the first argument.
                                    obj.toString()
                                }
                            } catch (e: Exception) {
                                raw
                            }
                        }
                        raw.startsWith("\"") -> try {
                            JSONTokener(raw).nextValue().toString()
                        } catch (e: Exception) {
                            raw.trim('"')
                        }
                        raw.startsWith("'") -> raw.trim('\'')
                        else -> raw
                    }
                }
            }
        }
        val sum = summary ?: return null
        return JSONObject()
            .put("summary", sum)
            .put("outputs", outputs)
    }

    /** Index of the paren that closes the one at [open],
     *  respecting quoted strings; -1 when unbalanced. */
    private fun matchingClose(s: String, open: Int): Int {
        var depth = 0
        var quote: Char? = null
        var esc = false
        for (i in open until s.length) {
            val ch = s[i]
            if (esc) {
                esc = false
                continue
            }
            val q = quote
            if (q != null) {
                if (ch == '\\') esc = true
                else if (ch == q) quote = null
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** Split on top-level commas, respecting quotes and
     *  bracket nesting. */
    private fun splitArgs(inner: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var esc = false
        for (ch in inner) {
            if (esc) {
                cur.append(ch)
                esc = false
                continue
            }
            val q = quote
            if (q != null) {
                cur.append(ch)
                if (ch == '\\') esc = true
                else if (ch == q) quote = null
                continue
            }
            when (ch) {
                '"', '\'' -> {
                    quote = ch
                    cur.append(ch)
                }
                '(', '[', '{' -> {
                    depth++
                    cur.append(ch)
                }
                ')', ']', '}' -> {
                    depth--
                    cur.append(ch)
                }
                ',' -> if (depth == 0) {
                    parts.add(cur.toString().trim())
                    cur.setLength(0)
                } else {
                    cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }
}
