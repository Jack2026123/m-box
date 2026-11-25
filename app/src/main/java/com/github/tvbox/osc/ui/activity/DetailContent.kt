package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.activity.BrowserActivity
import com.github.tvbox.osc.data.FollowDays
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.controlContentColor
import com.github.tvbox.osc.ui.components.controlSelectedContentColor
import com.github.tvbox.osc.ui.components.controlSurfaceModifier
import com.github.tvbox.osc.ui.components.filterChipElementId
import com.github.tvbox.osc.ui.components.FollowReminderSheet
import com.github.tvbox.osc.ui.components.isRatingNote
import com.github.tvbox.osc.ui.components.lineElementId
import com.github.tvbox.osc.ui.components.rememberHasWallpaper
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.util.PageJournal
import com.github.tvbox.osc.util.SiteHomePageResolver
import com.github.tvbox.osc.ui.page.jumpToSearch
import coil3.compose.AsyncImage

internal val HeroScrollFadeDistance = 300.dp

internal fun heroScrollProgress(firstVisibleIndex: Int, firstVisibleOffset: Int, referencePx: Int): Float = when {
    firstVisibleIndex > 0 -> 1f
    firstVisibleIndex < 0 -> 0f
    referencePx <= 0 -> 0f
    else -> (firstVisibleOffset.toFloat() / referencePx).coerceIn(0f, 1f)
}

@Composable
internal fun DetailContent(
    activity: DetailActivity,
    vm: DetailViewModel,
    revision: Int,
    listState: LazyListState,
    onPosterPic: (String) -> Unit,
    onSeed: (Int?) -> Unit,
) {
    val info = vm.vodInfo ?: return
    @Suppress("UNUSED_EXPRESSION") revision

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsState()
    val qualitySelected by vm.qualitySelected.collectAsState()
    val collected by vm.collected.collectAsState()
    val followRecord by vm.follow.collectAsState()
    val description = remember(info.des) { removeHtmlTag(info.des) }
    val directorNames = remember(info.director) { splitPersonNames(info.director) }
    val actorNames = remember(info.actor) { splitPersonNames(info.actor) }
    var followScheduleOpen by rememberSaveable { mutableStateOf(false) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey
    val homePageUrl = remember(currentSource) { SiteHomePageResolver.resolve(currentSource) }
    val heroRollPaused by vm.fullScreen.collectAsState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "hero") {
            DetailHero(
                title = info.name ?: "TVBox",
                altTitle = info.en,
                picture = info.pic,
                year = info.year,
                area = info.area,
                type = info.type,
                note = info.note,
                description = description,
                collected = collected,
                followed = followRecord != null,
                onPosterPic = onPosterPic,
                onSeed = onSeed,
                onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                onMusic = { activity.openMusicPlayer() },
                onCast = { activity.openCast() },
                onCollect = { vm.toggleCollect() },
                onFollow = { followScheduleOpen = true },
                onWebPlay = if (homePageUrl.isNullOrBlank()) null else {
                    { BrowserActivity.start(activity, homePageUrl, displaySourceName) }
                },
                rollPaused = heroRollPaused,
            )
        }

        if (directorNames.isNotEmpty() || actorNames.isNotEmpty()) {
            item(key = "director_actors") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    if (directorNames.isNotEmpty()) {
                        PersonRow(label = "导演", names = directorNames, onNameClick = { activity.jumpToSearch(it) })
                    }
                    if (actorNames.isNotEmpty()) {
                        PersonRow(label = "演员", names = actorNames, onNameClick = { activity.jumpToSearch(it) })
                    }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }

        if (qualityOptions.size > 1) {
            item(key = "quality") {
                ChipRow(
                    title = stringResource(R.string.detail_quality),
                    leading = { SectionTitleIcon(painterResource(R.drawable.ic_detail_quality)) },
                ) {
                    itemsIndexed(qualityOptions) { index, option ->
                        // 3.17：画质 chip 豁免取消——能点的胶囊框框全换轻透（用户 2026-11-15 14:47 定）。
                        // 总开关开时接控制层（提亮底 + 浅色描边 + 选中金字），1.5dp 蓝边删掉；
                        // 关时走原 detailChipBorder / detailChipColors。
                        val controlOn = ControlThemeStore.isControlThemeEnabled()
                        val qualityChipId = filterChipElementId("quality_" + option)
                        FilterChip(
                            modifier = Modifier.then(controlSurfaceModifier(qualityChipId, ControlElementKind.CHIP, index == qualitySelected)),
                            selected = index == qualitySelected,
                            onClick = { vm.onQualityClick(index, activity.playbackFacts()) },
                            label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                            shape = RoundedCornerShape(20.dp),
                            border = if (controlOn) null else detailChipBorder(selected = index == qualitySelected),
                            colors = if (controlOn) {
                                FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = controlContentColor(qualityChipId, ControlElementKind.CHIP),
                                    selectedContainerColor = Color.Transparent,
                                    selectedLabelColor = controlSelectedContentColor(qualityChipId, ControlElementKind.CHIP),
                                )
                            } else {
                                detailChipColors()
                            },
                        )
                    }
                }
            }
        }

        if (flags.isNotEmpty()) {
            item(key = "flags") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    ) {
                        SectionTitleIcon(painterResource(R.drawable.ic_detail_line))
                        Text(
                            text = stringResource(R.string.detail_line),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.detail_source, displaySourceName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .widthIn(max = 180.dp),
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                            LineCard(
                                name = flag.name ?: "",
                                count = info.seriesMap?.get(flag.name)?.size ?: 0,
                                selected = flag.name == currentFlag,
                                onClick = { vm.onFlagClick(flag.name ?: "") },
                            )
                        }
                    }
                }
            }
        }

        item(key = "sources") {
            SourceSection(vm, currentSourceName = displaySourceName, revision = revision)
        }

        item(key = "tmdb_info") {
            TmdbCastSection(info.name, info.en, info.year, actorNames, onNameClick = { activity.jumpToSearch(it) })
        }

        item(key = "tmdb_genres") {
            TmdbGenreSection(info.name, info.en, info.year)
        }
    }

    if (followScheduleOpen) {
        FollowReminderSheet(
            initialDays = followRecord?.let { FollowDays.decode(it.updateDays) }?.takeIf { it.isNotEmpty() }
                ?: setOf(0),
            initialHour = followRecord?.updateHour ?: 21,
            onDismissRequest = { followScheduleOpen = false },
            onSave = { days, hour -> vm.saveFollow(days, hour) },
        )
    }
}

private fun splitPersonNames(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val cleaned = raw
        // {...} 或 [...] 形前缀，收尾 、 或 /（JSON 内含 ] 也整段去；/ 形即树内 removeHtmlTag 已知的标记形）
        .replace(Regex("[\\[［]a=cr:(?:\\{.*?\\}|\\[.*?\\])[、/]?[\\]］]"), "")
        // 普通前缀
        .replace(Regex("[\\[［]a=cr:[^\\[\\]］]*[\\]］]"), "")
        // 收尾：[a]、[/a]、[、a] 及全角变体
        .replace(Regex("[\\[［][、，,/a﹑﹐\\s]*[\\]］]"), "")
    return cleaned.split(',', '，', '、', '/', '|')
        .map { it.trim() }
        .map { name ->
            var n = name
            // 每名首尾残留的 […] 碎片循环去掉
            while (true) {
                val stripped = n
                    .replace(Regex("^[\\[［][^\\[\\]］]*[\\]］]"), "")
                    .replace(Regex("[\\[［][^\\[\\]］]*[\\]］]$"), "")
                    .trim()
                if (stripped == n) break
                n = stripped
            }
            // 最终守门：名里不许含 [、]、a=cr、search:
            n.replace("[", "").replace("]", "")
                .replace("［", "").replace("］", "")
                .replace("a=cr", "").replace("search:", "")
                .trim()
        }
        .filter { it.isNotEmpty() && !it.matches(Regex("^[aAａＡ]+$")) }
}

@Composable
private fun PersonRow(
    label: String,
    names: List<String>,
    onNameClick: (String) -> Unit,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    nameColor: Color = MaterialTheme.colorScheme.primary,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val shown = if (expanded || names.size <= 3) names else names.take(3)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .then(if (names.size > 3) Modifier.clickable { expanded = !expanded } else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label + "：",
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
        )
        shown.forEachIndexed { index, name ->
            if (index > 0) {
                Text(
                    text = "、",
                    style = MaterialTheme.typography.bodyLarge,
                    color = labelColor,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = nameColor,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onNameClick(name) },
            )
        }
        if (names.size > 3 && !expanded) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun LineCard(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DetailItemCard(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.width(DetailItemWidth),
        // 3.07 百变控件：线路卡按线路名编号。
        controlId = lineElementId(name),
        controlKind = ControlElementKind.LINE,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.detail_line_video_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ChipRow(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            leading?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = if (leading != null) 8.dp else 0.dp),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// ---------------- 合并页详情（1.42：效果图拼起来的样子） ----------------
// 仅合并页使用，独立详情页仍走 DetailContent。1.42（用户 13:13 定、13:16 纠正、
// 13:24 以效果图为准覆盖）：合并页一屏一体——播放区在上（钉住，weight 1f，比例
// 不动）、详情区在下（weight 2f，纵向可滚，能滑出来看全）；红框内容整块去掉
// （信息卡 + 简介头 + ▶播放 + 三圆钮，全在框里，全去掉，不另留东西）；详情区
// 顺序（用户 13:30 亲定，线路在前、选集在后）：标题 + 信息行 + 简介
// （平铺、无卡；信息行按原来详情页：备注（去评分备注）· 年份 · 地区 · 类型，
// “ · ”分隔，简介 3 行截断不展开）→ 线路（来源：站源 + 主题胶囊卡片带
// “共 N 个视频”）→ 选集（倒序/全部+主题胶囊卡片，选集链路不动）→ 换源
// （SourceSection 原样）→ 导演阵容（TMDB job=Director 头像+人名，TMDB 关/无数据
// 时回落片源导演名、无头像用占位，两边都没有不显示）→ 演员阵容（TMDB 头像+人名，
// TMDB 关/无数据时不占位）→ 分类（TMDB 类型 chip，TMDB 关/无数据时不占位）
// → 海报（最后，所有分类的下面；通栏铺满、无框无边距、FillWidth 显示全、随详情
// 滚动看全；顶部不再叠两张海报）。
// 纵向可滚（userScrollEnabled=true，播放区在上钉住不动、详情区在下独立滚动，
// 不改成独立详情页、不动播放区）；横向卡片行仍可横滑，全部仍开选集表；
// 导演/演员折叠保持藏着（点整行展开/收起，无可见按钮）。
// 1.43：详情区三层叠加（内容在上、棕色层在中、海报在后）：棕色层（主题表面色）
// 留着不去掉，有海报时半透明保留棕色调、海报透出来；不再有单独的海报块。
// 播放区不再垫海报（只放视频/转圈）。海报兜底保留（pic 空时调用方用 pendingPic，
// 皆空则无背景图、棕色层回不透明实色）。
// 其余功能：换源（SourceSection）、搜索（导演阵容点名跳搜索）；收藏/追剧/画质
// 不在本版合并详情内（独立详情页仍有）。

// 无回弹 overscroll：滑到头不拉伸、不辉光，滚动到尽头即停。
private val NoBounceOverscroll = object : OverscrollEffect {
    override val isInProgress: Boolean = false
    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset = performScroll(delta)
    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        performFling(velocity)
    }
}

@Composable
internal fun MergedDetailContent(
    activity: DetailActivity,
    vm: DetailViewModel,
    revision: Int,
    listState: LazyListState,
    posterUrl: String? = null,
    posterSource: String? = null,
) {
    val info = vm.vodInfo ?: return
    @Suppress("UNUSED_EXPRESSION") revision

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val description = remember(info.des) { removeHtmlTag(info.des) }
    val directorNames = remember(info.director) { splitPersonNames(info.director) }
    val actorNames = remember(info.actor) { splitPersonNames(info.actor) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey
    val noteText = info.note

    // 1.42：顶部平铺块（标题 + 信息行 + 简介），无卡、无简介头、无播放钮、无圆钮；
    // 信息行 = 备注 · 年份 · 地区 · 类型（有哪项拼哪项，空项跳过）。
    val titleColor = MaterialTheme.colorScheme.onSurface
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sectionTitleColor = MaterialTheme.colorScheme.onSurface
    val sectionActionColor = MaterialTheme.colorScheme.primary

    // 1.50 当前页面记录：进详情页记详情（海报 URL 及来源、TMDB 状态、选集区类型），
    // 切集/换线记播放（线路、当前集）；内容取自本页正在用的状态，与屏上显示一致。
    LaunchedEffect(info.name, currentFlag) {
        PageJournal.detail(info.name, displaySourceName, currentFlag, episodes.size, posterUrl, posterSource)
    }
    LaunchedEffect(playIndex, currentFlag) {
        PageJournal.play(info.name, displaySourceName, currentFlag, episodes.getOrNull(playIndex)?.name, playIndex)
    }


    // 1.43：三层叠加（内容在上、棕色层在中、海报在后）：棕色层（主题表面色）
    // 留着不去掉；有海报时棕色层半透明（0.5）保留棕色调、压住海报鲜艳，
    // 海报透出来且文字可读；无海报时棕色层不透明、回原来的实色背景。
    // 1.48：壁纸＋全局透明开着时棕色层改透明（跟其他层同款 hasWallpaper && globalTransparent
    // 门），海报原图出，不再透出棕色调；关着时保持 1.43 原样。
    val hasWallpaper = rememberHasWallpaper()
    val globalTransparent = EffectsState.config.globalTransparent
    Box(modifier = Modifier.fillMaxSize()) {
        if (!posterUrl.isNullOrEmpty()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (hasWallpaper && globalTransparent) Color.Transparent
                    else if (posterUrl.isNullOrEmpty()) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surface.copy(alpha = EffectsState.config.detailVeilLevel / 100f),
                ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentPadding = PaddingValues(bottom = 32.dp),
            overscrollEffect = NoBounceOverscroll,
            userScrollEnabled = true,
        ) {
            // 1.42：顶部平铺块（效果图上图的样子）：标题 + 信息行 + 简介，无卡、
            // 无简介头、无播放钮、无圆钮、无导演/演员标注行；海报当背景铺在下面
            // （见 1.43 的 Box 背景层），上面不再叠两张海报。
            item(key = "merged_top") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    Text(
                        text = info.name ?: "TVBox",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val infoLine = listOf(
                        noteText?.trim()?.takeIf { it.isNotEmpty() && !isRatingNote(it) },
                        info.year.takeIf { it != 0 }?.toString(),
                        info.area?.takeIf { it.isNotBlank() },
                        info.type?.takeIf { it.isNotBlank() },
                    ).filterNotNull().joinToString(" · ")
                    if (infoLine.isNotBlank()) {
                        Text(
                            text = infoLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (description.isNotBlank()) {
                        var synopsisExpanded by remember(description) { mutableStateOf(false) }
                        Text(
                            text = "　　" + description.trimStart(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = bodyColor,
                            maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { synopsisExpanded = !synopsisExpanded },
                        )
                    }
                }
            }

            if (flags.isNotEmpty()) {
                item(key = "merged_flags") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_detail_line),
                                contentDescription = null,
                                tint = sectionTitleColor,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = stringResource(R.string.detail_line),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = sectionTitleColor,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = stringResource(R.string.detail_source, displaySourceName),
                                style = MaterialTheme.typography.labelLarge,
                                color = labelColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .widthIn(max = 180.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                                val selected = flag.name == currentFlag
                                val count = info.seriesMap?.get(flag.name)?.size ?: 0
                                DetailItemCard(
                                    selected = selected,
                                    onClick = { vm.onFlagClick(flag.name ?: "") },
                                    // 3.07 百变控件：合并页线路卡按线路名编号。
                                    controlId = lineElementId(flag.name ?: ""),
                                    controlKind = ControlElementKind.LINE,
                                ) {
                                    Text(
                                        text = flag.name ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (count > 0) {
                                        Text(
                                            text = "共 $count 个视频",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                item(key = "merged_episodes") {
                    EpisodeRow(vm, info, episodes, playIndex, currentFlag)

                }
            }

            item(key = "merged_sources") {
                SourceSection(vm, currentSourceName = displaySourceName, revision = revision)
            }

            // 1.40：导演阵容（原来详情页没有，新增；与演员阵容同款头像+人名、横向可滑，
            // 放演员阵容前面；优先 TMDB job=Director，TMDB 关/无数据时回落片源导演名，
            // 两边都没有则内部不显示，不造假数据）。
            item(key = "merged_tmdb_directors") {
                TmdbDirectorSection(info.name, info.en, info.year, directorNames, onNameClick = { activity.jumpToSearch(it) })
            }

            // 1.40：演员阵容 + 分类（原来详情页的 TMDB 区，原样复用独立详情页的
            // TmdbCastSection/TmdbGenreSection；TMDB 关或无数据时内部直接 return，不占位）。
            // 3.33：演员阵容加片源演员名兜底（与导演阵容同款），TMDB 搜不到的剧也能看到演员。
            item(key = "merged_tmdb_cast") {
                TmdbCastSection(info.name, info.en, info.year, actorNames, onNameClick = { activity.jumpToSearch(it) })
            }

            item(key = "merged_tmdb_genres") {
                TmdbGenreSection(info.name, info.en, info.year)
            }

        }
    }
}
