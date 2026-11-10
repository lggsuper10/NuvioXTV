package com.nuvio.tv.core.locallibrary

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.MetaCastMember
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "DoubanService"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

data class DoubanArtwork(
    val posterUrl: String?,
    val year: String? = null,
    val originalTitle: String? = null
)

data class DoubanArtworkCandidate(
    val subjectId: String,
    val title: String,
    val year: String?,
    val posterUrl: String,
    val episodeCount: Int?
)

data class DoubanDetails(
    val title: String?,
    val originalTitle: String?,
    val year: String?,
    val intro: String?,
    val directors: List<String>,
    val actors: List<String>,
    val genres: List<String>,
    val countries: List<String>,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val posterUrl: String?
) {
    fun toTmdbEnrichment(): TmdbEnrichment = TmdbEnrichment(
        localizedTitle = title,
        description = intro,
        genres = genres,
        backdrop = posterUrl,
        logo = null,
        poster = posterUrl,
        directorMembers = directors.map { MetaCastMember(name = it) },
        writerMembers = emptyList(),
        castMembers = actors.map { MetaCastMember(name = it) },
        releaseInfo = year,
        rating = rating,
        runtimeMinutes = runtimeMinutes,
        director = directors,
        writer = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = null,
        status = null,
        countries = countries,
        language = null,
        collectionId = null,
        collectionName = null,
        originalTitle = originalTitle,
        alternativeTitles = emptyList(),
        trailers = emptyList()
    )
}

/**
 * Free, no-key artwork/metadata fallback for networks where the TMDB API is
 * unreachable. This uses Douban's public suggestion and mobile detail JSON.
 * It is intentionally best-effort: any failure falls through to the caller.
 */
@Singleton
class DoubanService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tmdbSettings: TmdbSettingsDataStore
) {
    private data class SearchOutcome(val hit: DoubanSearchHit?)

    private data class DoubanSearchHit(
        val id: String,
        val title: String,
        val originalTitle: String?,
        val year: String?,
        val posterUrl: String?,
        val episodeCount: Int?
    )

    private val searchCache = ConcurrentHashMap<String, SearchOutcome>()

    suspend fun findArtwork(
        title: String,
        year: String?,
        isSeries: Boolean
    ): DoubanArtwork? = withContext(Dispatchers.IO) {
        if (!tmdbSettings.settings.value.useDouban) return@withContext null
        val hit = findBestHit(title, year, isSeries) ?: return@withContext null
        DoubanArtwork(
            posterUrl = hit.posterUrl,
            year = hit.year,
            originalTitle = hit.originalTitle
        )
    }

    suspend fun fetchDetails(
        title: String,
        year: String?,
        isSeries: Boolean
    ): DoubanDetails? = withContext(Dispatchers.IO) {
        if (!tmdbSettings.settings.value.useDouban) return@withContext null
        val hit = findBestHit(title, year, isSeries) ?: return@withContext null
        val type = if (isSeries || (hit.episodeCount ?: 0) > 0) "tv" else "movie"
        val mobileUrl = "https://m.douban.com/rexxar/api/v2/$type/${hit.id}"
        val abstractUrl = "https://movie.douban.com/j/subject_abstract?subject_id=${hit.id}"
        val mobile = fetchJson(
            url = mobileUrl,
            referer = "https://m.douban.com/$type/subject/${hit.id}/"
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        val abstract = fetchJson(
            url = abstractUrl,
            referer = "https://movie.douban.com/subject/${hit.id}/"
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (mobile == null && abstract == null) {
            return@withContext null
        }
        val details = buildDetails(hit, mobile, abstract)
        details
    }

    suspend fun searchArtworkCandidates(
        query: String,
        isSeries: Boolean
    ): List<DoubanArtworkCandidate> = withContext(Dispatchers.IO) {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return@withContext emptyList()
        for (candidateQuery in ArtworkQueryVariants.build(rawQuery)) {
            val candidates = searchArtworkCandidatesForQuery(candidateQuery, isSeries)
            if (candidates.isNotEmpty()) return@withContext candidates
        }
        emptyList()
    }

    private suspend fun searchArtworkCandidatesForQuery(
        cleanQuery: String,
        isSeries: Boolean
    ): List<DoubanArtworkCandidate> {
        val encoded = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
        val body = fetchJson(
            url = "https://movie.douban.com/j/subject_suggest?q=$encoded",
            referer = "https://movie.douban.com/"
        ) ?: return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").trim()
                val title = json.optString("title").trim()
                val poster = largePosterUrl(json.optString("img"))
                if (id.isBlank() || title.isBlank() || poster.isNullOrBlank()) continue
                add(
                    DoubanArtworkCandidate(
                        subjectId = id,
                        title = title,
                        year = json.optString("year").trim().takeIf { it.isNotBlank() },
                        posterUrl = poster,
                        episodeCount = json.optString("episode").toIntOrNull()
                    )
                )
            }
        }.sortedWith(
            compareByDescending<DoubanArtworkCandidate> {
                val hasEpisodes = (it.episodeCount ?: 0) > 0
                if (isSeries) hasEpisodes else !hasEpisodes
            }
        ).distinctBy { it.subjectId }.take(20)
    }

    private fun findBestHit(title: String, year: String?, isSeries: Boolean): DoubanSearchHit? {
        val rawQuery = title.trim()
        if (rawQuery.isBlank()) return null
        val cacheKey = "${if (isSeries) "tv" else "movie"}|${year.orEmpty()}|${rawQuery.lowercase()}"
        searchCache[cacheKey]?.let { return it.hit }

        for (query in ArtworkQueryVariants.build(rawQuery)) {
            findBestHitForQuery(query, year, isSeries)?.let { hit ->
                searchCache[cacheKey] = SearchOutcome(hit)
                return hit
            }
        }
        searchCache[cacheKey] = SearchOutcome(null)
        return null
    }

    private fun findBestHitForQuery(
        query: String,
        year: String?,
        isSeries: Boolean
    ): DoubanSearchHit? {
        if (query.isBlank()) return null
        val cacheKey = "${if (isSeries) "tv" else "movie"}|${year.orEmpty()}|${query.lowercase()}"
        searchCache[cacheKey]?.let { return it.hit }

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = fetchJson(
            url = "https://movie.douban.com/j/subject_suggest?q=$encoded",
            referer = "https://movie.douban.com/"
        ) ?: run {
            searchCache[cacheKey] = SearchOutcome(null)
            return null
        }
        val array = runCatching { JSONArray(body) }.getOrNull()
        if (array == null) {
            searchCache[cacheKey] = SearchOutcome(null)
            return null
        }
        val hits = buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    DoubanSearchHit(
                        id = id,
                        title = json.optString("title").trim(),
                        originalTitle = json.optString("sub_title")
                            .trim()
                            .takeIf { it.isNotBlank() },
                        year = json.optString("year").trim().takeIf { it.isNotBlank() },
                        posterUrl = largePosterUrl(json.optString("img")),
                        episodeCount = json.optString("episode").toIntOrNull()
                    )
                )
            }
        }
        if (hits.isEmpty()) {
            searchCache[cacheKey] = SearchOutcome(null)
            return null
        }

        val normalizedQuery = normalizeTitle(query)
        val selected = hits.maxWithOrNull(
            compareBy<DoubanSearchHit> { hit ->
                val names = listOfNotNull(hit.title, hit.originalTitle)
                    .map(::normalizeTitle)
                    .filter { it.isNotBlank() }
                when {
                    names.any { it == normalizedQuery } -> 8
                    names.any { it.contains(normalizedQuery) || normalizedQuery.contains(it) } -> 4
                    else -> 0
                }
            }.thenBy { hit ->
                if (!year.isNullOrBlank() && hit.year == year) 3 else 0
            }.thenBy { hit ->
                val hasEpisodes = (hit.episodeCount ?: 0) > 0
                when {
                    isSeries && hasEpisodes -> 2
                    !isSeries && !hasEpisodes -> 1
                    else -> 0
                }
            }
        )
        val selectedScore = selected?.let { hit ->
            val names = listOfNotNull(hit.title, hit.originalTitle)
                .map(::normalizeTitle)
                .filter { it.isNotBlank() }
            names.any { it == normalizedQuery || it.contains(normalizedQuery) || normalizedQuery.contains(it) }
        } == true
        val result = selected?.takeIf { selectedScore || hits.size == 1 }
        searchCache[cacheKey] = SearchOutcome(result)
        return result
    }

    private fun buildDetails(
        hit: DoubanSearchHit,
        mobile: JSONObject?,
        abstract: JSONObject?
    ): DoubanDetails {
        val title = mobile?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: abstract?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: hit.title
        val year = mobile?.optString("year")?.trim()?.takeIf { it.isNotBlank() }
            ?: abstract?.optString("release_year")?.trim()?.takeIf { it.isNotBlank() }
            ?: hit.year
        val intro = mobile?.optString("intro")?.trim()?.takeIf { it.isNotBlank() }
        val directors = abstract?.optJSONArray("directors").toStringList()
        val actors = abstract?.optJSONArray("actors").toStringList()
        val genres = (mobile?.optJSONArray("genres").toStringList() +
            abstract?.optJSONArray("types").toStringList()).distinct()
        val countries = (mobile?.optJSONArray("countries").toStringList() +
            abstract?.optString("region").orEmpty().split('/', '、', ',').map(String::trim))
            .filter { it.isNotBlank() }
            .distinct()
        val runtime = parseRuntimeMinutes(
            abstract?.optString("duration").orEmpty().ifBlank {
                mobile?.optString("duration").orEmpty()
            }
        )
        val mobileRating = mobile?.optJSONObject("rating")
            ?.optDouble("value", Double.NaN)
            ?: Double.NaN
        val rating = mobileRating.takeIf { !it.isNaN() }
            ?: abstract?.optString("rate")?.toDoubleOrNull()
        return DoubanDetails(
            title = title,
            originalTitle = hit.originalTitle,
            year = year,
            intro = intro,
            directors = directors,
            actors = actors,
            genres = genres,
            countries = countries,
            runtimeMinutes = runtime,
            rating = rating,
            posterUrl = hit.posterUrl
        )
    }

    private fun fetchJson(url: String, referer: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.onFailure { error ->
            Log.w(TAG, "Douban request failed: ${error.message}")
        }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun parseRuntimeMinutes(value: String): Int? =
        Regex("""(\d+)""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun largePosterUrl(value: String?): String? {
        val clean = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return clean
            .replace("/s_ratio_poster/", "/l_ratio_poster/")
            .replace("/m_ratio_poster/", "/l_ratio_poster/")
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
