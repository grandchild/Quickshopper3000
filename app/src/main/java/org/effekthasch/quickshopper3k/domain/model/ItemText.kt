package org.effekthasch.quickshopper3k.domain.model

import java.text.Normalizer

data class ItemInput(
    val name: String,
    val description: String = ""
)

data class ItemIdentity(
    val name: String,
    val description: String
)

object ItemText {
    private val combiningMarks = Regex("\\p{M}")
    private val whitespace = Regex("[\\p{javaWhitespace}\\p{Z}]+")
    private val synonymSeparators = Regex("[,\\p{javaWhitespace}\\p{Z}]+")
    private val descriptionBrackets = Regex("[()\\[\\]]")

    fun normalizeForSearch(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase()
    }

    fun normalizeDescription(description: String): String {
        return description.trim().replace(whitespace, " ")
    }

    fun normalizeSynonymText(synonyms: String): String = synonyms.trim()

    fun parseSynonyms(synonyms: String): List<String> {
        val seen = mutableSetOf<String>()
        return synonyms.split(synonymSeparators)
            .filter { it.isNotEmpty() }
            .filter { seen.add(normalizeForSearch(it)) }
    }

    fun identity(input: ItemInput): ItemIdentity {
        return ItemIdentity(
            name = normalizeForSearch(input.name.trim()),
            description = normalizeForSearch(normalizeDescription(input.description))
        )
    }

    fun blocksQuickAdd(items: Iterable<Item>, query: String): Boolean {
        val normalizedQuery = normalizeForSearch(query.trim())
        return items.any {
            normalizeForSearch(it.name.trim()) == normalizedQuery && it.description.isBlank()
        }
    }

    fun matches(item: Item, query: String): Boolean {
        val terms = query.trim()
            .split(whitespace)
            .filter { it.isNotEmpty() }
            .map(::normalizeForSearch)
        if (terms.isEmpty()) return true

        val values = buildList {
            add(normalizeForSearch(item.name))
            add(normalizeForSearch(item.description))
            addAll(parseSynonyms(item.synonyms).map(::normalizeForSearch))
        }
        return terms.all { term -> values.any { it.contains(term) } }
    }

    fun parseImportLine(text: String): ItemInput? {
        val line = stripImportBullets(text)
        if (line.isBlank()) return null

        val delimiter = findDelimiter(line)
        if (delimiter == null) return ItemInput(line.trim())

        val name = line.substring(0, delimiter.index).trim()
        val rawDescription = line.substring(delimiter.descriptionStart)
        val description = if (delimiter.removeBrackets) {
            rawDescription.replace(descriptionBrackets, "")
        } else {
            rawDescription
        }
        return ItemInput(name, normalizeDescription(description))
    }

    private fun stripImportBullets(text: String): String {
        var result = text.trim()
        while (result.startsWith("*") || result.startsWith("-") && !result.startsWith("-- ")) {
            result = result.drop(1).trimStart()
        }
        return result
    }

    private fun findDelimiter(line: String): Delimiter? {
        val delimiters = buildList {
            addOpeningDelimiter(line, '(', this)
            addOpeningDelimiter(line, '[', this)
            addTextDelimiter(line, " -- ", this)
            addTextDelimiter(line, " — ", this)
        }
        return delimiters.minByOrNull { it.index }
    }

    private fun addOpeningDelimiter(line: String, opening: Char, delimiters: MutableList<Delimiter>) {
        val index = line.indexOf(opening)
        if (index > 0 && line.substring(0, index).isNotBlank()) {
            delimiters.add(Delimiter(index, index + 1, removeBrackets = true))
        }
    }

    private fun addTextDelimiter(line: String, separator: String, delimiters: MutableList<Delimiter>) {
        val index = line.indexOf(separator)
        if (index > 0 && line.substring(0, index).isNotBlank()) {
            delimiters.add(Delimiter(index, index + separator.length, removeBrackets = false))
        }
    }

    private data class Delimiter(
        val index: Int,
        val descriptionStart: Int,
        val removeBrackets: Boolean
    )
}
