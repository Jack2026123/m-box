package com.github.tvbox.osc.util

/**
 * 当前页面记录（1.50 重新定义的实时日志）。
 * 规则：用户到哪个页面、点什么，就记那个页面的东西。点首页记首页，
 * 点播放记播放，点筛选记筛选，换源、切集每个动作记当时页面的关键状态。
 * 写 FloatLog，走同一条实时日志口子：面板实时日志页签与导出都带上。
 * 内容取自页面正在用的状态本身，所以日志内容就是屏上显示的东西。
 * 注意：不记完整播放地址（可能带 token），只记内核、线路、集数。
 */
object PageJournal {

    fun detail(
        title: String?,
        source: String?,
        flag: String?,
        episodeCount: Int,
        posterUrl: String?,
        posterSource: String?,
    ) {
        LOG.i("echo-page detail title=$title source=$source flag=$flag episodes=$episodeCount poster=$posterSource posterUrl=$posterUrl tmdb=${TmdbPoster.isActive()} tmdbKey=${KV.get(HawkConfig.TMDB_API_KEY, "").isNotBlank()} row=EpisodeRow")
    }

    fun play(
        title: String?,
        line: String?,
        flag: String?,
        episode: String?,
        episodeIndex: Int,
    ) {
        LOG.i("echo-page play title=$title line=$line flag=$flag episode=$episode episodeIndex=$episodeIndex")
    }

    fun kernel(kernel: String, decode: String) {
        LOG.i("echo-page kernel=$kernel decode=$decode")
    }

    fun home(source: String?) {
        LOG.i("echo-page home source=$source")
    }

    fun filter(page: String, selection: String) {
        LOG.i("echo-page filter page=$page selection=$selection")
    }
}
