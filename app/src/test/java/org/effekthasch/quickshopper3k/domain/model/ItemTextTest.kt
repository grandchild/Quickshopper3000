package org.effekthasch.quickshopper3k.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTextTest {
    @Test
    fun parsesImportDescriptions() {
        assertEquals(ItemInput("Tofu", "smoked"), ItemText.parseImportLine("Tofu (smoked)"))
        assertEquals(ItemInput("Tofu", "plain"), ItemText.parseImportLine("Tofu [plain]"))
        assertEquals(ItemInput("Tofu", "smoked"), ItemText.parseImportLine("Tofu -- smoked"))
        assertEquals(ItemInput("Tofu", "smoked"), ItemText.parseImportLine("Tofu — smoked"))
    }

    @Test
    fun usesEarliestImportDelimiter() {
        assertEquals(ItemInput("foo", "bar [baz]"), ItemText.parseImportLine("foo -- bar [baz]"))
        assertEquals(ItemInput("foo", "bar -- baz"), ItemText.parseImportLine("foo [bar -- baz]"))
    }

    @Test
    fun stripsBracketsAndNormalizesDescriptionWhitespace() {
        assertEquals(
            ItemInput("foo", "bar baz baq boo"),
            ItemText.parseImportLine("foo [bar)  baz\tbaq) (boo)")
        )
    }

    @Test
    fun preservesDelimiterWithoutNameAsLiteralName() {
        assertEquals(ItemInput("(smoked)"), ItemText.parseImportLine("(smoked)"))
        assertEquals(ItemInput("-- smoked"), ItemText.parseImportLine("-- smoked"))
    }

    @Test
    fun stripsImportBullets() {
        assertEquals(ItemInput("Tofu", "smoked"), ItemText.parseImportLine("- Tofu (smoked)"))
        assertEquals(ItemInput("Tofu"), ItemText.parseImportLine("*** Tofu"))
        assertNull(ItemText.parseImportLine(" - "))
    }

    @Test
    fun parsesAndDeduplicatesSynonyms() {
        assertEquals(
            listOf("soya", "Bean", "curd"),
            ItemText.parseSynonyms("soya, Bean\ncurd bean béan")
        )
    }

    @Test
    fun matchesEveryQueryTermAcrossFields() {
        val tofu = item("Tofu", description = "smoked", synonyms = "bean curd")

        assertTrue(ItemText.matches(tofu, "tofu"))
        assertTrue(ItemText.matches(tofu, "to sm"))
        assertTrue(ItemText.matches(tofu, "cur smo"))
        assertTrue(ItemText.matches(tofu, "to\u2003sm"))
        assertFalse(ItemText.matches(tofu, "to plain"))
        assertFalse(ItemText.matches(tofu, "bean,c"))
    }

    @Test
    fun searchIsCaseAndAccentInsensitive() {
        assertTrue(ItemText.matches(item("Äpfel"), "apf"))
        assertTrue(ItemText.matches(item("Cosmetics"), "SM"))
    }

    @Test
    fun formatsDescriptionForSharing() {
        assertEquals("Tofu", ItemText.formatForSharing(item("Tofu")))
        assertEquals("Tofu (smoked)", ItemText.formatForSharing(item("Tofu", "smoked")))
    }

    @Test
    fun quickAddIsBlockedOnlyByDescriptionlessExactName() {
        assertTrue(ItemText.blocksQuickAdd(listOf(item("Tofu")), "töfu"))
        assertFalse(ItemText.blocksQuickAdd(listOf(item("Tofu", "smoked")), "töfu"))
        assertTrue(
            ItemText.blocksQuickAdd(
                listOf(item("Tofu", "smoked"), item("TOFU")),
                "tofu"
            )
        )
        assertFalse(ItemText.blocksQuickAdd(listOf(item("Other", synonyms = "tofu")), "tofu"))
    }

    @Test
    fun identityIncludesNormalizedDescription() {
        assertEquals(
            ItemText.identity(ItemInput("Äpfel", "  Kleine\tTüte ")),
            ItemText.identity(ItemInput("apfel", "kleine tüte"))
        )
        assertFalse(
            ItemText.identity(ItemInput("Tofu")) ==
                    ItemText.identity(ItemInput("Tofu", "smoked"))
        )
    }

    private fun item(name: String, description: String = "", synonyms: String = "") = Item(
        id = 1,
        uuid = "item",
        name = name,
        description = description,
        synonyms = synonyms,
        state = ItemState.ACTIVATED,
        createdAt = 0
    )
}
