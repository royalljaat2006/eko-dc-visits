package com.eko.dcvisits.app

import com.eko.dcvisits.app.tracking.TestStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the Hindi translation: same keys as English and the same format placeholders. */
class StringResourcesTest {
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")

    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    @Test fun `every english string and plural has a hindi translation`() {
        val en = TestStrings.load()
        val hi = TestStrings.load("values-hi")
        assertEquals(emptySet(), en.keys - hi.keys, "missing in values-hi")
        assertEquals(emptySet(), hi.keys - en.keys, "extra in values-hi")
        assertEquals(TestStrings.loadPlurals().keys, TestStrings.loadPlurals("values-hi").keys)
    }

    @Test fun `hindi strings keep the english format placeholders`() {
        val en = TestStrings.load()
        val hi = TestStrings.load("values-hi")
        val mismatched = en.filter { (k, v) -> placeholders(v) != placeholders(hi.getValue(k)) }.keys
        assertTrue(mismatched.isEmpty(), "placeholder mismatch: $mismatched")
        val enPlurals = TestStrings.loadPlurals()
        TestStrings.loadPlurals("values-hi").forEach { (k, items) ->
            val want = placeholders(enPlurals.getValue(k).last())
            items.forEach { assertEquals(want, placeholders(it), "plural $k") }
        }
    }
}
