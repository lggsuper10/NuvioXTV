package com.nuvio.tv.core.locallibrary

import com.nuvio.tv.data.locallibrary.LocalMediaKind

object FilenameTitleParser {
    private val episodeRegex = Regex(
        """(?i)(?:^|[\s._-])(?:s(\d{1,2})[\s._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3}))(?:[\s._-]|$)"""
    )
    private val yearRegex = Regex("""(?:^|[\s._\[\]()-])((?:19|20)\d{2})(?:$|[\s._\[\]()+-])""")
    private val leadingNoise = Regex("""^[\s._\-\[\]()]+""")
    private val technicalTags = Regex(
        """(?i)\b(2160p|4k|1080p|720p|576p|480p|360p|x[\s._-]*26[45]|h[\s._-]*26[45]|hevc|av1|avc|10bit|8bit|uhd|hdr10\+?|dolbyvision|dolby[\s._-]*vision|dv|sdr|hdr|bluray|blu-ray|web-dl|webdl|webrip|web|hdtv|dvdrip|remux|brrip|bdrip|hddvd|dvd|itunes|ddp(?:\d(?:\.\d)?)?|dd\+|ddplus|truehd|atmos|dts-hd|dtshd|dts|ac3|aac|flac|eac3|lpcm|5\.1|7\.1|2\.0|stereo|mono|opus|vorbis|mp3|proper|repack|extended|uncut|unrated|remastered|imax|directors?.cut|multi|dual)\b"""
    )
    private val trailingReleaseGroup = Regex(
        """(?i)(?:[\s._-]+)(?:[a-z0-9]{1,20}(?:hd|uhd|hdr|hq)|ntb|flux|evo|yts(?:\.[a-z]+)?|rarbg|galaxyrg|tigole|qxr|psa|sva|ntg|fgt|cmrg|chd|wiki|beast|playweb|ion10)$"""
    )

    data class ParsedName(
        val title: String,
        val year: String?,
        val kind: LocalMediaKind,
        val season: Int?,
        val episode: Int?,
        val groupKey: String?
    )

    fun parse(fileName: String): ParsedName {
        val base = fileName.substringBeforeLast('.').trim().ifBlank { fileName.trim() }
        val episodeMatch = episodeRegex.find(base)
        val season = episodeMatch?.let {
            (it.groupValues[1].toIntOrNull() ?: it.groupValues[3].toIntOrNull())
        }
        val episode = episodeMatch?.let {
            (it.groupValues[2].toIntOrNull() ?: it.groupValues[4].toIntOrNull())
        }

        var working = if (episodeMatch != null) base.replace(episodeMatch.value, " ") else base
        val year = yearRegex.find(working)?.groupValues?.getOrNull(1)
        if (year != null) working = working.replace(year, " ")

        working = working
            .replace(technicalTags, " ")
            .replace(trailingReleaseGroup, " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\{[^}]*\}"""), " ")
            .replace(Regex("""\s+-\s*[\w.]+$"""), " ")
            .replace(leadingNoise, " ")
            .replace(Regex("""[\s._()\[\]-]+"""), " ")
            .trim()

        val title = working.ifBlank { base }
        val isSeries = season != null && episode != null
        return ParsedName(
            title = title,
            year = year,
            kind = if (isSeries) LocalMediaKind.SERIES_EPISODE else LocalMediaKind.MOVIE,
            season = season,
            episode = episode,
            groupKey = if (isSeries) normalizeKey(title) else null
        )
    }

    fun normalizeKey(title: String): String =
        title.lowercase().replace(Regex("""[\s._()\[\]{}:+-]+"""), "").trim()
}

