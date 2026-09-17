package com.videoshell.player

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup

/** 播放队列（跨 Activity 传递，避免 Intent 塞大对象） */
object PlayQueue {

    var title: String = ""
    var siteKey: String = ""
    var groups: List<PlayGroup> = emptyList()
    var groupIndex: Int = 0
    var episodeIndex: Int = 0

    fun episodes(): List<Episode> = groups.getOrNull(groupIndex)?.episodes.orEmpty()

    fun current(): Episode? = episodes().getOrNull(episodeIndex)

    fun hasNext(): Boolean = episodeIndex + 1 < episodes().size

    fun clear() {
        title = ""
        siteKey = ""
        groups = emptyList()
        groupIndex = 0
        episodeIndex = 0
    }
}

/** 嗅探到的候选媒体地址 */
data class SniffCandidate(val url: String, val type: String, var hits: Int = 1)
