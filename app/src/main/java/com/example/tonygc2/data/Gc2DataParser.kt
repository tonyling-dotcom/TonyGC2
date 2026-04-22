package com.example.tonygc2.data

import android.util.Log
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object Gc2DataParser {
    private const val TAG = "Gc2DataParser"

    /**
     * Parses the NMEA-style string from the Foresight GC2 and extracts:
     * - CarryDist
     * - TotalDist
     * - TotalSide
     * 
     * Note: The exact index of these fields in the GC2 NMEA string may vary 
     * depending on the firmware version or exact protocol used.
     * This parser assumes a comma-separated format.
     */
    fun parse(nmeaString: String): ShotData? {
        try {
            // Basic NMEA validation (starts with $ or similar, ends with checksum *XX)
            // Example GC2 string: $FSSHT,CarryDist,TotalDist,TotalSide,...*XX
            val cleanString = nmeaString.trim().trimStart('$').substringBefore('*')
            val parts = cleanString.split(",")

            // TODO: Update these indices based on the exact GC2 NMEA protocol documentation.
            // For now, we assume a placeholder structure or search for keywords if it's JSON.
            // If it's a standard NMEA string, we need the exact indices.
            // Let's assume indices 1, 2, 3 for Carry, Total, Offline for demonstration.
            if (parts.size >= 4) {
                // Assuming distances are in yards or need conversion. The PRD says "normalized to Yards".
                // If the GC2 outputs meters, we would multiply by 1.09361.
                // We'll assume the GC2 outputs yards by default, or we can add a conversion factor.
                val carryYards = parts[1].toDoubleOrNull()?.roundToInt() ?: 0
                val totalYards = parts[2].toDoubleOrNull()?.roundToInt() ?: 0
                val totalSide = parts[3].toDoubleOrNull() ?: 0.0

                val offlineString = formatOffline(totalSide)

                return ShotData(
                    carryDistance = carryYards,
                    totalDistance = totalYards,
                    offline = offlineString
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GC2 string: $nmeaString", e)
        }
        return null
    }

    /**
     * Converts the Total Side deviation into the required format:
     * Absolute number followed by "R" for positive (right) and "L" for negative (left).
     * Example: -5.0 -> "5L", 3.2 -> "3R", 0.0 -> "0"
     */
    private fun formatOffline(deviation: Double): String {
        val rounded = deviation.roundToInt()
        if (rounded == 0) return "0"
        
        val direction = if (deviation > 0) "R" else "L"
        return "${rounded.absoluteValue}$direction"
    }
}
