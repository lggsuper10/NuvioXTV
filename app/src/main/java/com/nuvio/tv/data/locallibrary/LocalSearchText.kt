package com.nuvio.tv.data.locallibrary

import com.nuvio.tv.core.tmdb.TmdbEnrichment
import java.util.Locale

private const val MAX_LOCAL_SEARCH_TEXT_LENGTH = 32_000
private val LOCAL_SEARCH_TITLE_SEPARATORS = Regex("""[\s._()\[\]{}:+\-]+""")

internal fun TmdbEnrichment.toLocalSearchText(): String = buildList {
    add(localizedTitle)
    add(originalTitle)
    addAll(alternativeTitles)
    add(description)
    add(releaseInfo)
    addAll(genres)
    addAll(director)
    addAll(writer)
    directorMembers.forEach { member ->
        add(member.name)
        add(member.character)
    }
    writerMembers.forEach { member ->
        add(member.name)
        add(member.character)
    }
    castMembers.forEach { member ->
        add(member.name)
        add(member.character)
    }
    productionCompanies.forEach { company -> add(company.name) }
    networks.forEach { network -> add(network.name) }
    addAll(countries.orEmpty())
    add(language)
    add(collectionName)
    add(status)
}
    .asSequence()
    .mapNotNull { it?.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString("\n")
    .take(MAX_LOCAL_SEARCH_TEXT_LENGTH)

internal fun normalizeLocalSearchTitle(value: String?): String =
    value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(LOCAL_SEARCH_TITLE_SEPARATORS, "")
        .trim()

internal fun matchesLocalSearchTitle(
    localTitle: String,
    localYear: String?,
    candidateTitle: String?,
    candidateOriginalTitle: String?,
    candidateYear: String?
): Boolean {
    val localKey = normalizeLocalSearchTitle(localTitle)
    if (localKey.isEmpty()) return false

    val localYearKey = localYear?.take(4)?.takeIf { it.length == 4 }
    val candidateYearKey = candidateYear?.take(4)?.takeIf { it.length == 4 }
    if (localYearKey != null && candidateYearKey != null && localYearKey != candidateYearKey) {
        return false
    }

    return sequenceOf(candidateTitle, candidateOriginalTitle)
        .mapNotNull { normalizeLocalSearchTitle(it).takeIf(String::isNotEmpty) }
        .any { it == localKey }
}
