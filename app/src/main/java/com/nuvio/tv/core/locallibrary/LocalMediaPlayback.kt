package com.nuvio.tv.core.locallibrary

import com.nuvio.tv.data.locallibrary.LocalMediaItemEntity
import com.nuvio.tv.ui.navigation.Screen
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaPlayback @Inject constructor() {
    fun playerRoute(
        item: LocalMediaItemEntity,
        contentId: String? = null,
        poster: String? = null,
        backdrop: String? = null
    ): String = Screen.Player.createRoute(
        streamUrl = item.fileUri,
        contentId = contentId,
        title = item.title.ifBlank { item.fileName },
        streamName = item.fileName,
        contentType = "local",
        contentName = item.title.ifBlank { item.fileName },
        poster = poster,
        backdrop = backdrop,
        videoId = item.id,
        filename = item.fileName,
        videoSize = item.fileSizeBytes,
        season = item.season,
        episode = item.episode,
        episodeTitle = if (item.kind == com.nuvio.tv.data.locallibrary.LocalMediaKind.SERIES_EPISODE) {
            item.fileName
        } else {
            null
        },
        startFromBeginning = false,
        streamDescription = item.fileName
    )
}
