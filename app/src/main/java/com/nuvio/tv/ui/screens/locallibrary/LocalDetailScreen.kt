@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.locallibrary.LocalArtworkCandidate
import com.nuvio.tv.data.locallibrary.LocalMediaItemEntity
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.LocalSourceBadge
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogButton
import com.nuvio.tv.ui.screens.detail.CastSection
import com.nuvio.tv.ui.screens.detail.HeroContentSection
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
fun LocalDetailScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    viewModel: LocalDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showPosterSearchDialog by remember { mutableStateOf(false) }
    // The global smooth bring-into-view spec scrolls a newly focused element to 42% of the
    // viewport. Inside the hero that would drag the action row from the bottom of the screen
    // up into the middle whenever focus moves between 播放 / 刷新海报 / 搜索海报, so the hero
    // item absorbs those requests instead (same protection the remote detail screen uses).
    // Absorbing them also keeps the list from scrolling back by itself, so focus returning to
    // the hero rewinds the page explicitly (see the hero Box below); otherwise everything
    // above the buttons — title, description — would stay scrolled out of reach.
    val heroNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        (uiState.backdropUri ?: uiState.posterUri)?.let { backdropUri ->
            AsyncImage(
                model = backdropUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                modifier = Modifier.fillMaxSize()
            )
        }
        LocalDetailBackdropGradients()

        when {
            uiState.isLoading -> LocalDetailStatus(
                text = stringResource(R.string.local_media_scanning)
            )

            uiState.error || (uiState.movie == null && uiState.episodes.isEmpty()) -> LocalDetailStatus(
                text = stringResource(R.string.local_detail_no_playable_items)
            )

            else -> {
                val movie = uiState.movie
                val episodes = uiState.episodes
                val primaryItem = movie ?: episodes.firstOrNull()
                if (primaryItem == null) {
                    LocalDetailStatus(text = stringResource(R.string.local_detail_no_playable_items))
                    return@Box
                }

                val title = uiState.title.ifBlank {
                    primaryItem.title.ifBlank { primaryItem.fileName }
                }
                val enrichment = uiState.enrichment
                val playLabel = if (primaryItem.season != null && primaryItem.episode != null) {
                    stringResource(
                        R.string.hero_play_episode,
                        primaryItem.season,
                        primaryItem.episode
                    )
                } else {
                    stringResource(R.string.local_detail_play)
                }
                val localFallbackDescription = if (movie != null) {
                    stringResource(R.string.local_detail_file, movie.fileName)
                } else {
                    listOf(
                        stringResource(R.string.local_detail_episodes),
                        stringResource(R.string.local_media_item_count, episodes.size)
                    ).joinToString(" \u00b7 ")
                }
                val description = enrichment?.description
                    ?.takeIf { it.isNotBlank() }
                    ?: localFallbackDescription
                val releaseInfo = enrichment?.releaseInfo ?: primaryItem.year
                val localMeta = Meta(
                    id = primaryItem.id,
                    type = if (movie != null) ContentType.MOVIE else ContentType.SERIES,
                    name = enrichment?.localizedTitle?.takeIf { it.isNotBlank() } ?: title,
                    poster = uiState.posterUri,
                    posterShape = PosterShape.LANDSCAPE,
                    background = uiState.backdropUri ?: uiState.posterUri,
                    logo = enrichment?.logo,
                    description = description,
                    releaseInfo = releaseInfo,
                    status = enrichment?.status,
                    imdbRating = enrichment?.rating?.toFloat(),
                    genres = enrichment?.genres.orEmpty(),
                    runtime = enrichment?.runtimeMinutes?.toString(),
                    director = enrichment?.director.orEmpty(),
                    writer = enrichment?.writer.orEmpty(),
                    cast = enrichment?.castMembers.orEmpty().map { it.name },
                    castMembers = enrichment?.castMembers.orEmpty(),
                    videos = emptyList(),
                    country = enrichment?.countries?.takeIf { it.isNotEmpty() }
                        ?.joinToString(" \u00b7 "),
                    awards = null,
                    language = enrichment?.language,
                    links = emptyList(),
                    released = releaseInfo
                )
                val nextToWatch = NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = null,
                    nextSeason = primaryItem.season,
                    nextEpisode = primaryItem.episode,
                    displayText = playLabel
                )
                val playAction = {
                    onPlay(viewModel.playerRoute(primaryItem))
                }

                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    item(key = "local_hero", contentType = "hero") {
                        Box(modifier = Modifier.bringIntoViewResponder(heroNoScrollResponder)) {
                            HeroContentSection(
                                meta = localMeta,
                                nextEpisode = null,
                                nextToWatch = nextToWatch,
                                onPlayClick = playAction,
                                isPlayEnabled = true,
                                isInLibrary = false,
                                onToggleLibrary = {},
                                onLibraryLongPress = {},
                                isMovieWatched = false,
                                isMovieWatchedPending = false,
                                onToggleMovieWatched = {},
                                hideMetaInfoImdb = false,
                                showFullReleaseDate = true,
                                playButtonFocusRequester = playFocusRequester,
                                onHeroActionFocused = {
                                    // Reached from below. The hero swallows bring-into-view
                                    // requests, so the page has to rewind itself; otherwise the
                                    // title and description stay scrolled off the top.
                                    if (listState.firstVisibleItemIndex > 0 ||
                                        listState.firstVisibleItemScrollOffset > 0
                                    ) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(0)
                                        }
                                    }
                                },
                                titleAccessory = {
                                    val sourceName = uiState.sourceName
                                    if (!sourceName.isNullOrBlank()) {
                                        LocalSourceBadge(sourceName = sourceName)
                                    }
                                },
                                showLibraryAction = false,
                                showWatchedAction = false,
                                extraActions = {
                                    LocalPosterActions(
                                        busy = uiState.artworkBusy,
                                        onRefresh = viewModel::refreshArtwork,
                                        onSearch = { showPosterSearchDialog = true }
                                    )
                                }
                            )
                        }
                    }

                    uiState.artworkResult?.let { result ->
                        item(key = "local_artwork_status") {
                            Text(
                                text = stringResource(
                                    if (result == LocalArtworkActionResult.UPDATED) {
                                        R.string.local_detail_artwork_updated
                                    } else {
                                        R.string.local_detail_artwork_failed
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (result == LocalArtworkActionResult.UPDATED) {
                                    NuvioTheme.colors.TextSecondary
                                } else {
                                    NuvioTheme.colors.TextTertiary
                                },
                                modifier = Modifier.padding(
                                    start = NuvioTheme.spacing.xxxl,
                                    top = NuvioTheme.spacing.sm
                                )
                            )
                        }
                    }

                    val leadingCast = enrichment?.directorMembers.orEmpty()
                    val cast = enrichment?.castMembers.orEmpty()
                    if (leadingCast.isNotEmpty() || cast.isNotEmpty()) {
                        item(key = "local_cast", contentType = "horizontal_row") {
                            CastSection(
                                cast = cast,
                                leadingCast = leadingCast,
                                listState = rememberLazyListState(),
                                title = stringResource(R.string.detail_tab_cast),
                                upFocusRequester = playFocusRequester,
                                windowResetKey = primaryItem.id,
                                modifier = Modifier.padding(
                                    start = NuvioTheme.spacing.xxxl,
                                    end = NuvioTheme.spacing.xxxl,
                                    top = NuvioTheme.spacing.lg
                                )
                            )
                        }
                    }

                    if (episodes.isNotEmpty()) {
                        item(key = "local_episodes_title", contentType = "episodes_title") {
                            Text(
                                text = stringResource(R.string.local_detail_episodes),
                                style = MaterialTheme.typography.titleLarge,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(
                                    start = NuvioTheme.spacing.xxxl,
                                    end = NuvioTheme.spacing.xxxl,
                                    top = NuvioTheme.spacing.xl,
                                    bottom = NuvioTheme.spacing.md
                                )
                            )
                        }
                        items(episodes, key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                onClick = { onPlay(viewModel.playerRoute(episode)) },
                                modifier = Modifier.padding(
                                    start = NuvioTheme.spacing.xxxl,
                                    end = NuvioTheme.spacing.xxxl,
                                    bottom = NuvioTheme.spacing.sm
                                )
                            )
                        }
                        item(key = "local_episodes_bottom_spacer") {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
                        }
                    }
                }

                LaunchedEffect(primaryItem.id) {
                    runCatching { playFocusRequester.requestFocus() }
                }
            }
        }
    }

    if (showPosterSearchDialog) {
        PosterSearchDialog(
            initialQuery = uiState.title,
            results = uiState.posterSearchResults,
            isSearching = uiState.isPosterSearching,
            searchCompleted = uiState.posterSearchCompleted,
            onSearch = viewModel::searchPoster,
            onSelect = { candidate ->
                viewModel.applyPoster(candidate)
                showPosterSearchDialog = false
                viewModel.clearPosterSearch()
            },
            onDismiss = {
                showPosterSearchDialog = false
                viewModel.clearPosterSearch()
            }
        )
    }
}

@Composable
private fun PosterSearchDialog(
    initialQuery: String,
    results: List<LocalArtworkCandidate>,
    isSearching: Boolean,
    searchCompleted: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (LocalArtworkCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { inputFocusRequester.requestFocus() }
        onSearch(query)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.local_detail_artwork_search_title),
        subtitle = stringResource(R.string.local_detail_artwork_search_subtitle),
        width = 980.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                onClick = { inputFocusRequester.requestFocus() },
                modifier = Modifier.weight(1f),
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                onSearch(query)
                                keyboardController?.hide()
                            }
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = NuvioTheme.colors.TextPrimary
                        ),
                        cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                        decorationBox = { innerTextField ->
                            if (query.isBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.local_detail_artwork_search_placeholder
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }
            NuvioDialogButton(
                text = stringResource(R.string.local_detail_artwork_search_action),
                onClick = {
                    onSearch(query)
                    keyboardController?.hide()
                },
                enabled = !isSearching,
                modifier = Modifier.width(140.dp)
            )
        }
        when {
            isSearching -> Text(
                text = stringResource(R.string.local_detail_artwork_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.md)
            )

            searchCompleted && results.isEmpty() -> Text(
                text = stringResource(R.string.local_detail_artwork_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.md)
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                items(results, key = { it.id }) { candidate ->
                    ArtworkCandidateRow(
                        candidate = candidate,
                        onClick = { onSelect(candidate) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkCandidateRow(
    candidate: LocalArtworkCandidate,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = candidate.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        candidate.year?.takeIf { it.isNotBlank() },
                        artworkSourceLabel(candidate.source)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun artworkSourceLabel(source: String): String = when (source) {
    "TMDB" -> stringResource(R.string.local_detail_artwork_source_tmdb)
    "DOUBAN" -> stringResource(R.string.local_detail_artwork_source_douban)
    else -> source
}

@Composable
private fun LocalPosterActions(
    busy: Boolean,
    onRefresh: () -> Unit,
    onSearch: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.height(52.dp),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.Secondary,
                focusedContentColor = NuvioTheme.colors.OnSecondary,
                disabledContainerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.55f),
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (busy) {
                            R.string.local_detail_artwork_refreshing
                        } else {
                            R.string.local_detail_refresh_poster
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Button(
            onClick = onSearch,
            enabled = !busy,
            modifier = Modifier.height(52.dp),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.Secondary,
                focusedContentColor = NuvioTheme.colors.OnSecondary,
                disabledContainerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.55f),
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.local_detail_search_poster),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun LocalDetailBackdropGradients() {
    val colors = NuvioTheme.colors
    val background = colors.Background
    // Light mode needs a bright scrim to keep the dark hero text readable, but washing
    // the whole frame white hides the artwork. Keep the left text column covered and
    // fade out early so the right half of the backdrop stays clear.
    val positions: List<Float>
    val alphas: List<Float>
    if (colors.isLight) {
        positions = listOf(0f, 0.18f, 0.34f, 0.48f, 0.58f, 0.68f, 0.78f, 0.88f, 1f)
        alphas = listOf(0.95f, 0.92f, 0.80f, 0.58f, 0.38f, 0.18f, 0.06f, 0.01f, 0f)
    } else {
        positions = listOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f)
        alphas = listOf(1f, 0.95f, 0.84f, 0.70f, 0.52f, 0.34f, 0.18f, 0.07f, 0f)
    }
    val leftStops = positions.zip(alphas) { position, alpha -> position to background.copy(alpha = alpha) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(*leftStops.toTypedArray()))
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0.03f),
                    0.52f to background.copy(alpha = 0f),
                    1f to background
                )
            )
    )
}

@Composable
private fun LocalDetailStatus(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.xxxl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: LocalMediaItemEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episodeLabel(episode),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = episode.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun episodeLabel(episode: LocalMediaItemEntity): String {
    val season = episode.season
    val number = episode.episode
    return if (season != null && number != null) {
        stringResource(R.string.local_detail_season_episode, season, number)
    } else if (number != null) {
        stringResource(R.string.local_detail_episode_title, number)
    } else {
        episode.fileName
    }
}
