package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStructureTest {

    @Test
    fun `playback sections follow the documented order`() {
        assertEquals(
            listOf(
                PlaybackSection.PLAYER,
                PlaybackSection.STREAM_SELECTION,
                PlaybackSection.UP_NEXT,
                PlaybackSection.SKIP_SEGMENTS,
                PlaybackSection.PLAYER_INTERFACE,
                PlaybackSection.AUDIO,
                PlaybackSection.SUBTITLES,
                PlaybackSection.VIDEO,
                PlaybackSection.BUFFER_NETWORK,
                PlaybackSection.P2P
            ),
            visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER))
        )
    }

    @Test
    fun `buffer and network is only shown for exoplayer based engines`() {
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER)))
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.AUTO)))
        assertFalse(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER)))
    }

    @Test
    fun `audio and video are separate sections`() {
        val sections = visiblePlaybackSections(PlayerSettings())
        assertTrue(PlaybackSection.AUDIO in sections)
        assertTrue(PlaybackSection.VIDEO in sections)
    }

    @Test
    fun `layout sections follow the documented order`() {
        assertEquals(
            listOf(
                LayoutSection.HOME,
                LayoutSection.CARDS,
                LayoutSection.CONTINUE_WATCHING,
                LayoutSection.DETAIL_PAGE,
                LayoutSection.EPISODES
            ),
            visibleLayoutSections(essentialMode = false)
        )
    }

    @Test
    fun `essential layout only offers the home layout`() {
        assertEquals(
            listOf(LayoutSection.ESSENTIAL_HOME),
            visibleLayoutSections(essentialMode = true)
        )
    }

    @Test
    fun `essential home is hidden in advanced mode`() {
        assertFalse(LayoutSection.ESSENTIAL_HOME in visibleLayoutSections(essentialMode = false))
    }

    @Test
    fun `first section is expanded by default`() {
        assertEquals(listOf(LayoutSection.HOME.name), defaultExpandedSections(essentialMode = false))
        assertEquals(listOf(LayoutSection.ESSENTIAL_HOME.name), defaultExpandedSections(essentialMode = true))
    }

    @Test
    fun `focused poster has no options on the grid layout`() {
        assertFalse(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.GRID).focusedPosterHasOptions())
        assertTrue(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.CLASSIC).focusedPosterHasOptions())
    }
}
