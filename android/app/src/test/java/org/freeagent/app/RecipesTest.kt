package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recipes: validation catches each error class, the
 * built-ins pass their own validation, substitution uses
 * values then defaults, and the success check judges per
 * kind.
 */
class RecipesTest {

    /** A minimal valid recipe; tests break one thing at
     *  a time from this shape. */
    private fun validRecipe(): JSONObject = JSONObject()
        .put("name", "test-recipe")
        .put("title", "Test recipe")
        .put("instructions", "Do the thing on {{date}}.")
        .put(
            "params",
            JSONArray().put(
                JSONObject()
                    .put("name", "date")
                    .put("type", "string")
                    .put("default", "today")
            )
        )
        .put(
            "tools",
            JSONArray().put("write_note").put("finish")
        )
        .put(
            "successCheck",
            JSONObject()
                .put("kind", "note_contains")
                .put("target", "out.txt")
                .put("pattern", "done")
        )
        .put("maxRetries", 1)

    // ---------- validate: the happy path ----------

    @Test
    fun validRecipePasses() {
        assertTrue(Recipes.validate(validRecipe()).isEmpty())
        assertNotNull(Recipes.parse(validRecipe()))
    }

    @Test
    fun builtinsPassValidate() {
        assertEquals(listOf("morning-brief", "watch-page"), Recipes.builtinNames())
        for (name in Recipes.builtinNames()) {
            val builtin = Recipes.builtin(name)
            assertNotNull("builtin $name exists", builtin)
            assertEquals(
                "builtin $name validates: " +
                    Recipes.validate(builtin!!),
                emptyList<String>(),
                Recipes.validate(builtin)
            )
            assertNotNull(Recipes.parse(builtin))
        }
        assertNull(Recipes.builtin("no-such-recipe"))
    }

    @Test
    fun builtinShapesMatchTheSpec() {
        val morning = Recipes.builtin("morning-brief")!!
        assertEquals("daily 07:00", morning.optString("schedule"))
        assertEquals(1, morning.optInt("maxRetries"))
        assertEquals(
            "morning-brief.txt",
            morning.getJSONObject("successCheck").optString("target")
        )
        assertEquals(
            "Today",
            morning.getJSONObject("successCheck").optString("pattern")
        )
        assertTrue(morning.optString("instructions").contains("Today, in short:"))

        val watch = Recipes.builtin("watch-page")!!
        assertTrue(watch.isNull("schedule"))
        assertEquals(
            "watch-result.txt",
            watch.getJSONObject("successCheck").optString("target")
        )
        assertEquals(
            "watch:",
            watch.getJSONObject("successCheck").optString("pattern")
        )
        val paramNames = mutableListOf<String>()
        val params = watch.getJSONArray("params")
        for (i in 0 until params.length()) {
            paramNames.add(params.getJSONObject(i).optString("name"))
        }
        assertEquals(listOf("url", "pattern"), paramNames)
    }

    // ---------- validate: each error class ----------

    @Test
    fun missingOrBlankNameIsAnError() {
        val noName = JSONObject()
            .put("instructions", "Do it.")
            .put("tools", JSONArray().put("finish"))
        assertTrue(Recipes.validate(noName).isNotEmpty())
        val blank = validRecipe().put("name", "  ")
        assertTrue(Recipes.validate(blank).any { it.contains("name") })
        assertNull(Recipes.parse(blank))
    }

    @Test
    fun malformedNameIsAnError() {
        for (bad in listOf("Morning Brief", "UPPER", "has space", "under_score", "")) {
            val recipe = validRecipe().put("name", bad)
            assertTrue(
                "name \"$bad\" should fail",
                Recipes.validate(recipe).any { it.contains("name") }
            )
        }
        // Boundary: 48 chars passes, 49 fails.
        assertTrue(
            Recipes.validate(validRecipe().put("name", "a".repeat(48))).isEmpty()
        )
        assertTrue(
            Recipes.validate(validRecipe().put("name", "a".repeat(49))).isNotEmpty()
        )
    }

    @Test
    fun missingInstructionsIsAnError() {
        val recipe = validRecipe().put("instructions", "")
        assertTrue(
            Recipes.validate(recipe).any { it.contains("instructions") }
        )
        assertNull(Recipes.parse(recipe))
    }

    @Test
    fun paramMissingNameOrTypeIsAnError() {
        val noName = validRecipe().put(
            "params",
            JSONArray().put(JSONObject().put("type", "string"))
        )
        assertTrue(Recipes.validate(noName).isNotEmpty())
        val noType = validRecipe().put(
            "params",
            JSONArray().put(JSONObject().put("name", "x"))
        )
        assertTrue(Recipes.validate(noType).isNotEmpty())
    }

    @Test
    fun paramUnknownTypeIsAnError() {
        val recipe = validRecipe().put(
            "params",
            JSONArray().put(
                JSONObject().put("name", "x").put("type", "date")
            )
        )
        assertTrue(
            Recipes.validate(recipe).any { it.contains("type") }
        )
        // All three known types pass.
        for (type in listOf("string", "number", "boolean")) {
            val ok = validRecipe().put(
                "params",
                JSONArray().put(
                    JSONObject().put("name", "x").put("type", type)
                )
            )
            assertTrue(Recipes.validate(ok).isEmpty())
        }
    }

    @Test
    fun emptyOrUnknownToolsAreErrors() {
        val empty = validRecipe().put("tools", JSONArray())
        assertTrue(
            Recipes.validate(empty).any { it.contains("tools") }
        )
        val unknown = validRecipe().put(
            "tools",
            JSONArray().put("write_note").put("teleport")
        )
        assertTrue(
            Recipes.validate(unknown).any { it.contains("teleport") }
        )
    }

    @Test
    fun unknownSuccessCheckKindIsAnError() {
        val recipe = validRecipe().put(
            "successCheck",
            JSONObject().put("kind", "vibes").put("pattern", "x")
        )
        assertTrue(
            Recipes.validate(recipe).any { it.contains("successCheck") }
        )
        for (kind in listOf("note_contains", "page_contains", "always")) {
            val ok = validRecipe().put(
                "successCheck",
                JSONObject().put("kind", kind).put("pattern", "x")
            )
            assertTrue(Recipes.validate(ok).isEmpty())
        }
    }

    @Test
    fun maxRetriesOutsideRangeIsAnError() {
        assertTrue(
            Recipes.validate(validRecipe().put("maxRetries", 4)).isNotEmpty()
        )
        assertTrue(
            Recipes.validate(validRecipe().put("maxRetries", -1)).isNotEmpty()
        )
        assertTrue(
            Recipes.validate(validRecipe().put("maxRetries", 0)).isEmpty()
        )
        assertTrue(
            Recipes.validate(validRecipe().put("maxRetries", 3)).isEmpty()
        )
    }

    // ---------- parse: defaults filled ----------

    @Test
    fun parseFillsDefaultsWithoutMutatingInput() {
        val raw = JSONObject()
            .put("name", "bare")
            .put("instructions", "Just do it.")
            .put("tools", JSONArray().put("finish"))
        val parsed = Recipes.parse(raw)!!
        assertEquals(0, parsed.getJSONArray("params").length())
        assertEquals(0, parsed.optInt("maxRetries"))
        assertEquals(
            "always",
            parsed.getJSONObject("successCheck").optString("kind")
        )
        // The input keeps its gaps — parse returns a copy.
        assertFalse(raw.has("params"))
        assertFalse(raw.has("successCheck"))
    }

    // ---------- substitute ----------

    @Test
    fun substituteUsesValuesThenDefaults() {
        val recipe = validRecipe()
        // Caller's value wins over the default…
        assertEquals(
            "Do the thing on Friday.",
            Recipes.substitute(recipe, mapOf("date" to "Friday"))
        )
        // …and the default fills in when no value is given.
        assertEquals(
            "Do the thing on today.",
            Recipes.substitute(recipe, emptyMap())
        )
    }

    @Test
    fun substituteFallsBackToEmptyAndLeavesUnknownTokens() {
        val recipe = JSONObject()
            .put("name", "sub-test")
            .put(
                "instructions",
                "Open {{url}} and keep {{mystery}} as-is."
            )
            .put(
                "params",
                JSONArray().put(
                    JSONObject().put("name", "url").put("type", "string")
                )
            )
            .put("tools", JSONArray().put("browser_open").put("finish"))
        // Declared param with neither value nor default → "".
        assertEquals(
            "Open  and keep {{mystery}} as-is.",
            Recipes.substitute(recipe, emptyMap())
        )
        // Unknown tokens stay even when a value is offered.
        assertEquals(
            "Open https://example.com and keep {{mystery}} as-is.",
            Recipes.substitute(
                recipe,
                mapOf("url" to "https://example.com", "mystery" to "solved")
            )
        )
    }

    @Test
    fun substituteDrivesTheWatchPageBuiltin() {
        val watch = Recipes.builtin("watch-page")!!
        val text = Recipes.substitute(
            watch,
            mapOf("url" to "https://example.com", "pattern" to "Sold out")
        )
        assertTrue(text.contains("https://example.com"))
        assertTrue(text.contains("Sold out"))
        assertFalse(text.contains("{{"))
    }

    // ---------- checkPasses ----------

    @Test
    fun checkPassesPerKind() {
        val always = JSONObject()
            .put("successCheck", JSONObject().put("kind", "always"))
        assertTrue(Recipes.checkPasses(always, null))
        assertTrue(Recipes.checkPasses(always, "anything"))

        val noteCheck = validRecipe() // note_contains, pattern "done"
        assertTrue(Recipes.checkPasses(noteCheck, "It is DONE now"))
        assertFalse(Recipes.checkPasses(noteCheck, "still working"))
        assertFalse(Recipes.checkPasses(noteCheck, null))

        val pageCheck = validRecipe().put(
            "successCheck",
            JSONObject()
                .put("kind", "page_contains")
                .put("pattern", "Sold out")
        )
        assertTrue(Recipes.checkPasses(pageCheck, "Item sold OUT today"))
        assertFalse(Recipes.checkPasses(pageCheck, "In stock"))
    }

    @Test
    fun checkPassesMissingPatternFails() {
        val recipe = validRecipe().put(
            "successCheck",
            JSONObject().put("kind", "note_contains").put("target", "x.txt")
        )
        assertFalse(Recipes.checkPasses(recipe, "any probe text"))
    }

    @Test
    fun checkPassesWithoutCheckIsAlways() {
        val recipe = JSONObject().put("name", "bare")
        assertTrue(Recipes.checkPasses(recipe, null))
    }
}
