package com.videoshell.data.model

/**
 * 聚合搜索网格里的一行（v1.0.39）。
 *
 * ## 为什么需要它
 *
 * 「搜全站源」的结果是**按站分块**铺出来的。如果块与块之间在视觉上连成一片，用户看到的
 * 就是一个四五十条的大列表 —— 而"这条来自甲站、那条来自乙站"恰好是这个功能唯一要传达的
 * 信息（同一部剧在几个站上都有，用户要选的从来是**哪个站**）。
 *
 * v1.0.37 把站名塞进了卡片副标题，但那是 11sp 的灰字、还排在"分类/年份/地区"后面，
 * 一屏扫过去看不出分块从哪里开始。所以升格成**独立的一行分组标题**。
 *
 * ## 为什么是密封类，而不是给 [VideoItem] 加个 `isHeader` 字段
 *
 * [VideoItem] 是**解析层**的产物（适配器从网页里抠出来的东西），而"这是不是分组标题"
 * 纯属界面排版概念。混进 [VideoItem] 会让每个解析器的每条构造都多背一个恒为 false 的字段，
 * 也会让离线 harness 里那些 Java 位置构造全部失效。
 *
 * 分组标题**不计入**卡片数：[VideoItem] 的那套断言（限量、同源、顺序）在 `AggSearch`
 * 里仍然以卡片为单位成立，只是界面上多了一层"行"的包装。
 */
sealed class VideoRow {

    /**
     * 分组标题：某个站那一整块卡片的开头。
     *
     * @param siteKey 该站的 key（与卡片上的 `siteKey` 同源）
     * @param name    站名（空名站点在 [com.videoshell.data.site.AggSearch.rows] 里已回落到 key）
     * @param count   这一块的**卡片数**（不含本行）
     */
    data class Header(
        val siteKey: String,
        val name: String,
        val count: Int
    ) : VideoRow()

    /** 一张影片卡片 */
    data class Card(val item: VideoItem) : VideoRow()
}
