package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SearchChain (v0.10): parsers for routes 2 and 3 of the
 * on-device search chain (Wikipedia OpenSearch, Open Library).
 * Fixtures are trimmed recordings of the real APIs' shapes.
 */
class SearchChainTest {

    @Test
    fun wikipediaParsesTitlesDescriptionsUrls() {
        val json = """
            ["lagos",
             ["Lagos", "Lagos State"],
             ["Lagos is the most populous city in Nigeria.", "A state in Nigeria."],
             ["https://en.wikipedia.org/wiki/Lagos",
              "https://en.wikipedia.org/wiki/Lagos_State"]]
        """.trimIndent()
        val out = SearchChain.parseWikipediaOpenSearch(json)
        assertTrue(out!!.startsWith("Lagos — Lagos is the most populous city in Nigeria."))
        assertTrue(out.contains("(https://en.wikipedia.org/wiki/Lagos)"))
        assertTrue(out.contains("Lagos State"))
    }

    @Test
    fun wikipediaEmptyOrBrokenReturnsNull() {
        assertNull(SearchChain.parseWikipediaOpenSearch("""["q",[],[],[]]"""))
        assertNull(SearchChain.parseWikipediaOpenSearch("not json"))
        assertNull(SearchChain.parseWikipediaOpenSearch("""["q"]"""))
    }

    @Test
    fun wikipediaCapsAtThreeResults() {
        val json = """
            ["q",
             ["A", "B", "C", "D"],
             ["a", "b", "c", "d"],
             ["u1", "u2", "u3", "u4"]]
        """.trimIndent()
        val out = SearchChain.parseWikipediaOpenSearch(json)!!
        assertEquals(3, out.lines().size)
    }

    @Test
    fun openLibraryParsesTitleAuthorYear() {
        val json = """
            {"docs":[
              {"title":"Things Fall Apart",
               "author_name":["Chinua Achebe"],
               "first_publish_year":1958},
              {"title":"No Longer at Ease",
               "author_name":["Chinua Achebe"],
               "first_publish_year":1960}
            ]}
        """.trimIndent()
        val out = SearchChain.parseOpenLibrary(json)!!
        assertTrue(out.startsWith("Things Fall Apart by Chinua Achebe (1958)"))
        assertTrue(out.contains("No Longer at Ease"))
    }

    @Test
    fun openLibraryEmptyOrBrokenReturnsNull() {
        assertNull(SearchChain.parseOpenLibrary("""{"docs":[]}"""))
        assertNull(SearchChain.parseOpenLibrary("{}"))
        assertNull(SearchChain.parseOpenLibrary("not json"))
    }

    @Test
    fun openLibraryToleratesMissingAuthorAndYear() {
        val out = SearchChain.parseOpenLibrary("""{"docs":[{"title":"Solo Title"}]}""")
        assertEquals("Solo Title", out)
    }
}
