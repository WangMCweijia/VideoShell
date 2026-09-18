package com.videoshell.player

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup

/** 播放队列（跨 Activity 传递，避免 Intent 塞大对象） */
object PlayQueue {

    var title: String = ""
    var siteKey: String = ""
    /** 详情页影片 id（播放历史回跳详情用） */
    var vid: String = ""
    /** 封面（播放历史展示用） */
    var pic: String = ""
    var groups: List<PlayGroup> = emptyList()
    var groupIndex: Int = 0
    var episodeIndex: Int = 0

    fun episodes(): List<Episode> = groups.getOrNull(groupIndex)?.episodes.orEmpty()

    fun current(): Episode? = episodes().getOrNull(episodeIndex)

    fun hasNext(): Boolean = episodeIndex + 1 < episodes().size

    fun clear() {
        title = ""
        siteKey = ""
        vid = ""
        pic = ""
        groups = emptyList()
        groupIndex = 0
        episodeIndex = 0
    }
}

// 嗅探候选（SniffCandidate）与候选队列（SniffQueue）见 SniffRank.kt
