package com.smath.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object TimeUtils {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val dateFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    fun formatRelative(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val now = LocalDate.now()
        return when {
            date == now -> timeFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
            date == now.minusDays(1) -> "昨天"
            date.year == now.year -> dateFmt.format(date)
            else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        }
    }

    fun formatDateTime(millis: Long): String {
        val dt = Instant.ofEpochMilli(millis).atZone(zone)
        return "${dt.monthValue}月${dt.dayOfMonth}日 ${timeFmt.format(dt)}"
    }

    fun dateGroup(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val now = LocalDate.now()
        val weekStart = now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val monthStart = now.withDayOfMonth(1)
        return when {
            date == now -> "今天"
            date == now.minusDays(1) -> "昨天"
            !date.isBefore(weekStart) -> "本周"
            !date.isBefore(monthStart) -> "本月"
            else -> "更早"
        }
    }

    fun dateFolderName(millis: Long): String = dateGroup(millis)

    fun startOfDay(millis: Long): Long {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}