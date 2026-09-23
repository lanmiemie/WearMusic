package com.shijiu.wearmusic.util

import kotlin.math.abs

/** 解析后的单行歌词。 */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * 极简 LRC 解析器：支持 `[mm:ss.xx]` / `[mm:ss.xxx]` 多时间标签行，
 * 以及 `[offset:±ms]` 全局偏移。解析失败返回空列表（界面显示「暂无歌词」）。
 */
object LrcParser {

    private val timeTagRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetRegex = Regex("""\[offset:\s*(-?\d+)\s*]""")

    fun parse(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        val offset = offsetRegex.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LrcLine>()
        raw.lines().forEach { line ->
            val tags = timeTagRegex.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty() || text.startsWith("<")) return@forEach
            tags.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionRaw = match.groupValues[3]
                val fraction = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLongOrNull() ?: 0L
                    2 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> (fractionRaw.take(3).toLongOrNull() ?: 0L)
                } * when (fractionRaw.length) {
                    0, 1, 2 -> 10L
                    else -> 1L
                }
                val timeMs = minutes * 60_000 + seconds * 1_000 + fraction - offset
                lines.add(LrcLine(timeMs.coerceAtLeast(0), text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 当前时间对应的行下标（无命中返回 -1）。 */
    fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) index = i else break
        }
        return index
    }

    /**
     * 把行级翻译歌词（tlyric）对齐到歌词行：取与每行开始时间最近且
     * 相差小于 [TRANSLATION_MATCH_TOLERANCE_MS] 的翻译，无匹配为空串。
     * 逐字（yrc）与行级（lrc）歌词行都按此对齐。
     */
    fun alignTranslations(lineStartsMs: List<Long>, translationRaw: String): List<String> {
        if (lineStartsMs.isEmpty()) return emptyList()
        val translationByTime = HashMap<Long, String>()
        parse(translationRaw).forEach { line -> translationByTime[line.timeMs] = line.text }
        if (translationByTime.isEmpty()) return List(lineStartsMs.size) { "" }
        val times = translationByTime.keys.sorted()
        return lineStartsMs.map { start ->
            times.minByOrNull { abs(it - start) }
                ?.takeIf { abs(it - start) < TRANSLATION_MATCH_TOLERANCE_MS }
                ?.let { translationByTime[it] } ?: ""
        }
    }

    private const val TRANSLATION_MATCH_TOLERANCE_MS = 800L
}
