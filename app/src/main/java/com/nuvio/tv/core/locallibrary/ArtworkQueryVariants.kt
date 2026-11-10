package com.nuvio.tv.core.locallibrary

/**
 * Builds progressively simpler search phrases from a filename or a title typed by
 * the user. TMDB and Douban both match full release names poorly, so callers try
 * the cleaned title first and fall back to shorter, language-specific variants.
 */
object ArtworkQueryVariants {
    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "ts", "m2ts",
        "mts", "vob", "mpg", "mpeg", "3gp", "rm", "rmvb", "iso"
    )
    private val whitespace = Regex("""\s+""")
    private val brackets = Regex("""\[[^\[\]]*\]|\{[^{}]*\}""")
    private val trailingYear = Regex("""(?:^|[\s._-])(?:19|20)\d{2}\s*$""")

    // Release-name noise that commonly survives the filename parser. The values
    // use Unicode escapes so this file remains portable across source encodings.
    private val localizedNoise = Regex(
        "(?i)(" + listOf(
            "\u56fd\u8bed",
            "\u7ca4\u8bed",
            "\u53f0\u8bed",
            "\u56fd\u914d",
            "\u4e2d\u6587\u5b57\u5e55",
            "\u4e2d\u82f1\u53cc\u5b57",
            "\u4e2d\u82f1\u5b57\u5e55",
            "\u53cc\u8bed",
            "\u4e2d\u5b57",
            "\u5b57\u5e55",
            "\u9ad8\u6e05",
            "\u8d85\u6e05",
            "\u84dd\u5149",
            "\u5b8c\u6574\u7248",
            "\u52a0\u957f\u7248",
            "\u5bfc\u6f14\u526a\u8f91\u7248",
            "\u672a\u5220\u51cf",
            "\u4fee\u590d\u7248",
            "\u91cd\u5236\u7248"
        ).joinToString("|") { Regex.escape(it) } + ")"
    )
    private val englishNoise = Regex(
        """(?i)\b(web[\s._-]*dl|webrip|blu[\s._-]*ray|bdrip|brrip|remux|hdtv|x26[45]|h26[45]|hevc|av1|2160p|1080p|720p|4k|uhd|hdr10\+?|hdr|dolby[\s._-]*vision|ddp(?:\d(?:\.\d)?)?|atmos|truehd|dts(?:-hd)?|aac|ac3|eac3|remastered|extended|unrated|proper|repack)"""
    )
    private val cjkCharacter = Regex("""[\u3400-\u9fff]""")
    private val edgePunctuation = charArrayOf(' ', '.', '-', '_', ':', '\uFF1A', '|', '/')

    fun build(raw: String): List<String> {
        val input = raw.trim()
        if (input.isBlank()) return emptyList()
        val extension = input.substringAfterLast('.', "").lowercase()
        val parsed = if (extension in videoExtensions) {
            runCatching { FilenameTitleParser.parse(input) }.getOrNull()
        } else {
            null
        }
        val base = (parsed?.title?.takeIf { it.isNotBlank() } ?: input)
            .let(::clean)
        if (base.isBlank()) return emptyList()

        val variants = linkedSetOf<String>()
        fun add(value: String?) {
            val normalized = value
                ?.let(::clean)
                ?.takeIf { it.length >= 2 }
                ?: return
            if (variants.none { it.equals(normalized, ignoreCase = true) }) {
                variants += normalized
            }
        }

        add(base)
        add(base.substringBefore(':').substringBefore('\uFF1A'))

        val tokens = base.split(whitespace).filter { it.isNotBlank() }
        if (tokens.size > 1) {
            val chinese = tokens.filter { cjkCharacter.containsMatchIn(it) }.joinToString(" ")
            val nonChinese = tokens.filterNot { cjkCharacter.containsMatchIn(it) }.joinToString(" ")
            add(chinese)
            add(nonChinese)
        }

        // A release name often keeps an English title after a localized title.
        // Prefer the shorter side only when both sides carry useful characters.
        if (variants.size > 1) {
            val sorted = variants.sortedBy { it.length }
            if (sorted.first().length >= 4) add(sorted.first())
        }
        return variants.take(4)
    }

    private fun clean(value: String): String =
        value
            .replace(brackets, " ")
            .replace(localizedNoise, " ")
            .replace(englishNoise, " ")
            .replace(trailingYear, " ")
            .replace(whitespace, " ")
            .trim(*edgePunctuation)
}
