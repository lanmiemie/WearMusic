package com.shijiu.wearmusic.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间与数量格式化工具。 */
object TimeFmt {

    /** 毫秒 → mm:ss（超过 1 小时为 h:mm:ss）。 */
    fun mmss(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** 数量 → 1.2万 / 3456。 */
    fun count(n: Long): String = when {
        n >= 100_000_000 -> trimZero(n / 100_000_000.0) + "亿"
        n >= 10_000 -> trimZero(n / 10_000.0) + "万"
        else -> n.toString()
    }

    /** 文件大小 → MB。 */
    fun fileSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> trimZero(bytes / 1024.0 / 1024.0) + " MB"
        bytes >= 1 shl 10 -> trimZero(bytes / 1024.0) + " KB"
        else -> "$bytes B"
    }

    private fun trimZero(v: Double): String =
        if (v >= 100) v.toInt().toString()
        else String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    /** 毫秒时间戳 → yyyy-MM-dd。 */
    fun date(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** 毫秒时间戳 → MM-dd HH:mm。 */
    fun dateTime(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

    /** 历史日推的可选日期串（最近 [days] 天，含今天），元素为 yyyy-MM-dd。 */
    fun recentDates(days: Int = 14): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val result = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for (i in 0 until days) {
            result.add(fmt.format(Date(now - i * 24L * 3600 * 1000)))
        }
        return result
    }
}
