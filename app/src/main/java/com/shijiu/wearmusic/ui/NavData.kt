package com.shijiu.wearmusic.ui

import com.ohmusic.app.data.model.Song

/**
 * 跨页面传递的临时数据（进程内），用于歌曲菜单、心动模式种子等
 * 不适合放进路由参数的对象；进程被杀后各处都会回退到按 id 拉取。
 */
object NavData {

    /** 歌曲菜单 / 添加到歌单的目标歌曲。 */
    var pendingSong: Song? = null

    /** 心动模式：种子歌曲 id + 参照歌单 id。 */
    var heartSeed: Pair<Long, Long>? = null

    /** 待处理队列上下文标记。 */
    var pendingQueueTag: String? = null

    fun clear() {
        pendingSong = null
        heartSeed = null
        pendingQueueTag = null
    }
}
