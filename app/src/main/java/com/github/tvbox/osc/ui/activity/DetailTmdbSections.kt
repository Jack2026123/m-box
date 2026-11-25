package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.fxGroupBevel
import com.github.tvbox.osc.util.TmdbApi
import com.github.tvbox.osc.util.TmdbPoster

private val CastAvatarSize = 80.dp

private val CastItemWidth = 92.dp

private val CastSheetAvatarSize = 96.dp

private val CastSheetLoadingSize = 40.dp

@Composable
internal fun TmdbCastSection(
    name: String?,
    altName: String?,
    year: Int,
    fallbackNames: List<String>,
    onNameClick: (String) -> Unit,
) {
    val detail = rememberTmdbDetail(name, altName, year)
    val tmdbCast = detail?.cast.orEmpty()
    when {
        tmdbCast.isNotEmpty() -> TmdbCastBlock(tmdbCast)
        // 3.33：TMDB 没这部剧的演员数据时，回落片源演员名（与导演阵容同款兜底），
        // 不然 TMDB 搜不到的剧演员阵容整个不显示；两边都没有则内部不显示。
        fallbackNames.isNotEmpty() -> TmdbCastBlock(
            fallbackNames.map { TmdbApi.TmdbCastMember(id = 0, name = it, character = "", profilePath = "") },
            onNameClick = onNameClick,
        )
        else -> return
    }
}

@Composable
internal fun TmdbGenreSection(name: String?, altName: String?, year: Int) {
    val detail = rememberTmdbDetail(name, altName, year) ?: return
    if (detail.genres.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_genres))
            Text(
                text = stringResource(R.string.detail_genres),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(detail.genres) { genre -> TmdbGenreChip(genre) }
        }
    }
}

@Composable
internal fun TmdbDirectorSection(
    name: String?,
    altName: String?,
    year: Int,
    fallbackNames: List<String>,
    onNameClick: (String) -> Unit,
) {
    val detail = rememberTmdbDetail(name, altName, year)
    val tmdbDirectors = detail?.directors.orEmpty()
    var selected by remember { mutableStateOf<TmdbApi.TmdbCastMember?>(null) }
    val rows: List<DirectorRowItem>? = when {
        tmdbDirectors.isNotEmpty() -> tmdbDirectors.map { member ->
            DirectorRowItem(
                name = member.name,
                profilePath = member.profilePath,
                onClick = { if (member.id > 0) selected = member else onNameClick(member.name) },
            )
        }
        fallbackNames.isNotEmpty() -> fallbackNames.map { directorName ->
            DirectorRowItem(
                name = directorName,
                profilePath = "",
                onClick = { onNameClick(directorName) },
            )
        }
        else -> null
    }
    if (rows == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_actors))
            Text(
                text = "导演阵容",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { it.name + "|" + it.profilePath }) { row ->
                TmdbDirectorItem(name = row.name, profilePath = row.profilePath, onClick = row.onClick)
            }
        }
    }
    selected?.let { member -> TmdbCastSheet(member = member, onDismiss = { selected = null }) }
}

private data class DirectorRowItem(
    val name: String,
    val profilePath: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TmdbDirectorItem(
    name: String,
    profilePath: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CastItemWidth),
    ) {
        if (profilePath.isNotEmpty()) {
            AsyncImage(
                model = TmdbPoster.profileUrl(profilePath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(CastAvatarSize)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .clickable(onClick = onClick)
                    .background(detailCardColor()),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(CastAvatarSize)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(detailCardColor())
                    .clickable(onClick = onClick),
            ) {
                Text(
                    text = name.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun TmdbGenreChip(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = detailCardColor(),
        // Small-tier relief: bevel + sheen only, no platform halo.
        modifier = Modifier.fxGroupBevel(RoundedCornerShape(20.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun rememberTmdbDetail(name: String?, altName: String?, year: Int): TmdbApi.TmdbDetail? {
    if (name.isNullOrBlank()) return null
    val epoch by TmdbPoster.configEpoch.collectAsState()
    var detail by remember(name, altName, year, epoch) {
        mutableStateOf(TmdbPoster.cachedDetail(name, year, altName))
    }
    LaunchedEffect(name, altName, year, epoch) {
        if (detail == null) detail = TmdbPoster.resolveDetail(name, year, altName)
    }
    return detail
}

@Composable
private fun TmdbCastBlock(
    cast: List<TmdbApi.TmdbCastMember>,
    onNameClick: ((String) -> Unit)? = null,
) {
    var selected by remember { mutableStateOf<TmdbApi.TmdbCastMember?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_actors))
            Text(
                text = stringResource(R.string.detail_cast),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cast, key = { it.name + "|" + it.profilePath }) { member ->
                // 片源兜底的名（id = 0，无 TMDB 详情）点名跳搜索，不开空的演员弹窗。
                TmdbCastItem(member, onClick = { if (member.id > 0) selected = member else onNameClick?.invoke(member.name) })
            }
        }
    }
    selected?.let { member ->
        TmdbCastSheet(member = member, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TmdbCastItem(member: TmdbApi.TmdbCastMember, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CastItemWidth),
    ) {
        AsyncImage(
            model = TmdbPoster.profileUrl(member.profilePath),
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(CastAvatarSize)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .clickable(onClick = onClick)
                .background(detailCardColor()),
        )
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

private sealed interface CastPersonState {
    object Loading : CastPersonState

    data class Ready(val person: TmdbApi.TmdbPerson?) : CastPersonState
}

@Composable
private fun rememberCastPerson(id: Int): CastPersonState {
    if (id <= 0) return CastPersonState.Ready(null)
    val epoch by TmdbPoster.configEpoch.collectAsState()
    var state by remember(id, epoch) {
        mutableStateOf(
            TmdbPoster.cachedPerson(id)?.let { CastPersonState.Ready(it) } ?: CastPersonState.Loading,
        )
    }
    LaunchedEffect(id, epoch) {
        if (state is CastPersonState.Loading) {
            state = CastPersonState.Ready(TmdbPoster.resolvePerson(id))
        }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TmdbCastSheet(member: TmdbApi.TmdbCastMember, onDismiss: () -> Unit) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        val state = rememberCastPerson(member.id)
        val person = (state as? CastPersonState.Ready)?.person
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = TmdbPoster.profileUrl(
                        person?.profilePath?.takeIf { it.isNotEmpty() } ?: member.profilePath,
                    ),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(CastSheetAvatarSize)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(detailCardColor()),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = person?.name?.takeIf { it.isNotBlank() } ?: member.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val meta = listOfNotNull(
                        person?.birthday?.takeIf { it.isNotBlank() },
                        person?.placeOfBirth?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (state is CastPersonState.Loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(CastSheetLoadingSize))
                }
            } else {
                val biography = person?.biography.orEmpty()
                if (biography.isNotBlank()) {
                    Text(
                        text = biography,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
