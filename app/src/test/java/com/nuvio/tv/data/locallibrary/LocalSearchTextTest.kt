package com.nuvio.tv.data.locallibrary

import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.MetaCastMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTextTest {

    @Test
    fun `search text includes localized and original metadata`() {
        val enrichment = TmdbEnrichment(
            localizedTitle = "流浪地球",
            description = "太阳即将毁灭，人类开启流浪地球计划。",
            genres = listOf("科幻", "冒险"),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = listOf(MetaCastMember(name = "吴京", character = "刘培强")),
            releaseInfo = "2019",
            rating = 7.8,
            runtimeMinutes = 125,
            director = listOf("郭帆"),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            status = "Released",
            countries = listOf("CN"),
            language = "zh",
            collectionId = null,
            collectionName = null,
            originalTitle = "The Wandering Earth",
            alternativeTitles = listOf("The Wandering Earth")
        )

        val searchText = enrichment.toLocalSearchText()

        assertTrue(searchText.contains("流浪地球"))
        assertTrue(searchText.contains("The Wandering Earth"))
        assertTrue(searchText.contains("太阳即将毁灭"))
        assertTrue(searchText.contains("吴京"))
        assertTrue(searchText.contains("郭帆"))
    }

    @Test
    fun `search text omits blank fields`() {
        val enrichment = TmdbEnrichment(
            localizedTitle = " ",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            status = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )

        assertEquals("", enrichment.toLocalSearchText())
    }

    @Test
    fun `local title matches translated tmdb candidate through original title`() {
        assertTrue(
            matchesLocalSearchTitle(
                localTitle = "Superman",
                localYear = "2025",
                candidateTitle = "\u8D85\u4EBA",
                candidateOriginalTitle = "Superman",
                candidateYear = "2025"
            )
        )
    }

    @Test
    fun `local title match rejects a different release year`() {
        assertFalse(
            matchesLocalSearchTitle(
                localTitle = "Superman",
                localYear = "2025",
                candidateTitle = "Superman",
                candidateOriginalTitle = null,
                candidateYear = "1978"
            )
        )
    }
}
