package com.github.tvbox.osc.reader

import com.github.tvbox.osc.bean.VodInfo
import org.json.JSONObject

/**
 * Diversion hook between the video playback pipeline and the readers.
 * PlaybackFetch offers every fresh, non-cast play result to the
 * registered router; when the result classifies as comic/novel content
 * the router opens the matching reader and the video pipeline stops.
 * Only the detail page registers a router (music/live pages keep the
 * legacy video behavior).
 */
interface ReaderRouter {

    /** Returns true when [content] was consumed by a reader. */
    fun openReader(vod: VodInfo, content: ReaderContent, playResult: JSONObject?): Boolean
}
