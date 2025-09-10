package net.exoad.kira.utils

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date


object Chronos {
    fun formatTimestamp(milliseconds: Long = System.currentTimeMillis(), format: String? = null): String {
        return (if (format == null) defaultFormatter else SimpleDateFormat(format)).format(
            Date.from(
                Instant.ofEpochMilli(
                    milliseconds
                )
            )
        )
    }

    private val defaultFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
}

