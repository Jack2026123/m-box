package com.github.tvbox.osc.reader

import com.github.tvbox.osc.bean.VodInfo
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ReaderEpisode(
    val name: String,
    val url: String,
)

/**
 * One open book/comic: everything a reader screen needs to move between
 * chapters without going back through the detail page. Held in-process
 * (episode lists can be thousands of entries, too big for an Intent) and
 * referenced by a token in the launch Intent.
 */
data class ReaderSession(
    val token: String,
    val sourceKey: String,
    val vodId: String,
    val title: String,
    val picture: String?,
    val playFlag: String?,
    val episodes: List<ReaderEpisode>,
    val startIndex: Int,
    val firstContent: ReaderContent,
    val firstResult: JSONObject?,
    /** Non-null when chapters come from the Legado engine (reading mode). */
    val legadoBookKey: String? = null,
) {
    /** Stable per-book key used for bookmarks/progress/settings scoping. */
    val bookKey: String = sourceKey + "|" + vodId + "|" + (playFlag ?: "")

    companion object {

        fun fromVod(
            vod: VodInfo,
            content: ReaderContent,
            result: JSONObject?,
        ): ReaderSession {
            val list = vod.seriesMap?.get(vod.playFlag).orEmpty()
            val episodes = list.map { ReaderEpisode(it.name ?: "", it.url ?: "") }
            val index = vod.playIndex.coerceIn(0, maxOf(0, episodes.size - 1))
            return ReaderSession(
                token = UUID.randomUUID().toString(),
                sourceKey = vod.sourceKey ?: "",
                vodId = vod.id ?: "",
                title = vod.name ?: "",
                picture = vod.pic,
                playFlag = vod.playFlag,
                episodes = episodes,
                startIndex = index,
                firstContent = content,
                firstResult = result,
            )
        }
    }
}

object ReaderSessions {

    private val sessions = ConcurrentHashMap<String, ReaderSession>()

    fun put(session: ReaderSession): String {
        sessions[session.token] = session
        return session.token
    }

    fun get(token: String?): ReaderSession? = token?.let { sessions[it] }

    fun remove(token: String?) {
        if (token != null) sessions.remove(token)
    }
}
