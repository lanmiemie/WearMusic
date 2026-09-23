package com.shijiu.wearmusic.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 逐字歌词的「词/字」片段。 */
data class YrcWord(
    val startMs: Long,
    /** 词的结束时间：以下一个词的起点为准（官方时长普遍拖尾，不精确）。 */
    val endMs: Long,
    val text: String,
    /** 词首字符在整行文本中的下标。 */
    val charStart: Int,
    val charCount: Int
)

/** 逐字歌词的单行。 */
data class YrcLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<YrcWord>,
    val text: String
)

/**
 * 网易云 yrc 逐字歌词解析器。
 *
 * 两种行格式：
 *  1. 元数据行（JSON）：`{"t":0,"c":[{"tx":"作词: "},{"tx":"唐恬"}]}`，
 *     无词级时间戳，整行作为一个静态行渲染；
 *  2. 逐字行：`[行起,行长](词起,词长,0)词(词起,词长,0)词…`，
 *     词与词的时间戳紧密衔接。
 */
object YrcParser {

    private val headerRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordRegex = Regex("""\((\d+),(\d+),\d+\)""")

    private val jsonLine = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<YrcLine> {
        if (raw.isBlank()) return emptyList()
        val lines = mutableListOf<YrcLine>()
        raw.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> return@forEach
                line.startsWith("{") -> parseJsonLine(line)?.let(lines::add)
                else -> parseKaraokeLine(line)?.let(lines::add)
            }
        }
        return lines.sortedBy { it.startMs }
    }

    private fun parseKaraokeLine(line: String): YrcLine? {
        val header = headerRegex.find(line) ?: return null
        val startMs = header.groupValues[1].toLongOrNull() ?: return null
        val duration = header.groupValues[2].toLongOrNull() ?: 0L
        val body = header.groupValues[3]

        val matches = wordRegex.findAll(body).toList()
        if (matches.isEmpty() || matches.first().range.first != 0) return null

        val words = mutableListOf<YrcWord>()
        val sb = StringBuilder()
        matches.forEachIndexed { wi, match ->
            val wordStart = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            val wordDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(wi + 1)?.range?.first ?: body.length
            val wordText = body.substring(textStart, textEnd)
            if (wordText.isEmpty()) return@forEachIndexed
            // 词的结束时间取下一个词的起点：官方词长普遍带拖尾，
            // 直接使用会让「已唱」停留在末字上不够干脆。
            val nextStart = matches.getOrNull(wi + 1)?.groupValues?.get(1)?.toLongOrNull()
            val wordEnd = nextStart ?: (wordStart + wordDuration)
            words.add(
                YrcWord(
                    startMs = wordStart,
                    endMs = wordEnd,
                    text = wordText,
                    charStart = sb.length,
                    charCount = wordText.length
                )
            )
            sb.append(wordText)
        }
        val text = sb.toString().trimEnd()
        if (text.isEmpty()) return null // 纯时间戳的空行（间奏占位）
        return YrcLine(
            startMs = startMs,
            endMs = startMs + duration,
            words = words,
            text = text
        )
    }

    private fun parseJsonLine(line: String): YrcLine? = runCatching {
        val obj = jsonLine.parseToJsonElement(line).jsonObject
        val startMs = obj["t"]?.jsonPrimitive?.longOrNull ?: return null
        val text = buildString {
            obj["c"]?.jsonArray?.forEach { element ->
                val tx = (element as? JsonObject)?.get("tx")?.jsonPrimitive?.contentOrNull
                if (tx != null) append(tx)
            }
        }.trim()
        if (text.isEmpty()) return null
        // 元数据行无词级时间：给一个固定展示窗，行级高亮按整行点亮。
        YrcLine(startMs = startMs, endMs = startMs + 4_000, words = emptyList(), text = text)
    }.getOrNull()

    /** 当前时间所在的行下标（无命中返回 -1）。 */
    fun activeIndex(lines: List<YrcLine>, positionMs: Long): Int {
        var index = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= positionMs) index = i else break
        }
        return index
    }

    /**
     * 行内已唱「字符位置」浮点值：整数部分 = 已完整唱完的字符数，
     * 小数部分 = 正在唱字符内的推进比例（0..1），供 Canvas 平滑填充。
     */
    fun charProgressAt(line: YrcLine, positionMs: Long): Float {
        val total = line.text.length
        if (total == 0) return 0f
        if (positionMs <= line.startMs) return 0f
        if (positionMs >= line.endMs) return total.toFloat()
        // 元数据行没有词级时间戳：进入行内即整行点亮。
        if (line.words.isEmpty()) return total.toFloat()
        for (word in line.words) {
            if (positionMs < word.startMs) return word.charStart.toFloat()
            if (positionMs < word.endMs) {
                val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                val fraction = (positionMs - word.startMs).toFloat() / span
                return word.charStart + fraction * word.charCount
            }
        }
        return total.toFloat()
    }
}
