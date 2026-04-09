package ym.globalchain.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object TimeFormats {

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun formatTimestamp(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
    }

    fun formatRemaining(nowMillis: Long, targetMillis: Long): String {
        val remaining = (targetMillis - nowMillis).coerceAtLeast(0L)
        if (remaining <= 0L) {
            return "已到期"
        }
        var seconds = TimeUnit.MILLISECONDS.toSeconds(remaining)
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        seconds %= 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += "${days}天"
        if (hours > 0) parts += "${hours}小时"
        if (minutes > 0) parts += "${minutes}分"
        if (parts.isEmpty() || (days == 0L && hours == 0L)) {
            parts += "${seconds}秒"
        }
        return parts.take(3).joinToString(" ")
    }
}
