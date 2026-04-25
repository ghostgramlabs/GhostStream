package com.ghostgramlabs.directserve.core.resources.util

import android.content.Context
import com.ghostgramlabs.directserve.core.resources.R

object LocalizationUtils {
    
    /**
     * Formats a byte count into a human-readable string (e.g., "1.2 GB").
     */
    fun formatBytes(context: Context, bytes: Long): String {
        if (bytes <= 0L) {
            return context.getString(
                R.string.common_byte_format_integer,
                0,
                context.getString(R.string.common_unit_b)
            )
        }
        
        val units = arrayOf(
            R.string.common_unit_b,
            R.string.common_unit_kb,
            R.string.common_unit_mb,
            R.string.common_unit_gb,
            R.string.common_unit_tb
        )
        
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        
        val unitString = context.getString(units[unitIndex])
        return if (value >= 100 || unitIndex == 0) {
            context.getString(R.string.common_byte_format_integer, value.toInt(), unitString)
        } else {
            context.getString(R.string.common_byte_format_decimal, value, unitString)
        }
    }

    /**
     * Formats a duration in milliseconds into a human-readable string (e.g., "5m 30s").
     */
    fun formatDuration(context: Context, millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> context.getString(R.string.common_duration_format_h_m, hours, minutes)
            minutes > 0 -> context.getString(R.string.common_duration_format_m_s, minutes, seconds)
            else -> context.getString(R.string.common_duration_format_s, seconds)
        }
    }

    /**
     * Formats a duration in milliseconds into a timestamp string (e.g., "5:30" or "1:05:30").
     */
    fun formatDurationTimestamp(context: Context, millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            context.getString(R.string.common_duration_timestamp_h_m_s, hours, minutes, seconds)
        } else {
            context.getString(R.string.common_duration_timestamp_m_s, minutes, seconds)
        }
    }
}
