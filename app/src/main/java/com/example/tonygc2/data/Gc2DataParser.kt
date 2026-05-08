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
            // Check if it's the standard GC2 data format: CT=...,SN=...,...,CY=...,TL=...
            if (nmeaString.contains("CY=") && nmeaString.contains("TL=")) {
                val parts = nmeaString.split(",")
                
                var carryYards = 0.0
                var totalYards = 0.0
                var offlineYards = 0.0
                var azimuth = 0.0
                var ballSpeed = 0.0
                var totalSpin = 0.0
                var elevation = 0.0
                var sideSpin = 0.0
                var backSpin = 0.0
                
                for (part in parts) {
                    val keyValue = part.split("=")
                    if (keyValue.size == 2) {
                        val key = keyValue[0].trim()
                        val value = keyValue[1].trim().toDoubleOrNull() ?: 0.0
                        
                        when (key) {
                            "CY" -> carryYards = value // Carry distance
                            "TL" -> totalYards = value // Total distance
                            "AZ" -> azimuth = value    // Azimuth (Launch direction L/R)
                            "SP" -> ballSpeed = value  // Ball Speed
                            "TS" -> totalSpin = value  // Total Spin
                            "EL" -> elevation = value  // Elevation / Launch Angle
                            "SS" -> sideSpin = value   // Side Spin
                            "BS" -> backSpin = value   // Back Spin
                        }
                    }
                }

                // The GC2 often sends "heartbeat" or empty data frames where CY and TL are 0.0 or very close to 0
                // We should ignore these and only update the UI when a real shot is detected.
                Log.d(TAG, "Parsed GC2 Data -> Carry: $carryYards, Total: $totalYards, Azimuth: $azimuth, Ball Speed: $ballSpeed, Elevation: $elevation, Total Spin: $totalSpin, Side Spin: $sideSpin, Back Spin: $backSpin")

                if (ballSpeed < 2.0 && carryYards < 1.0) {
                    Log.d(TAG, "Ignoring empty/heartbeat data frame")
                    return null
                }

                // Claude's Physics Formula for Total Distance and Offline
                val vFps = ballSpeed * 1.46667 // Convert mph to ft/s
                val theta = Math.toRadians(elevation)
                val phi = Math.toRadians(azimuth)
                val rpm = if (backSpin > 0.0) backSpin else totalSpin
                val g = 32.2
                val mu = 0.25 // fairway friction coefficient

                // Phase 2 - Roll distance calculation
                val vLand = vFps * kotlin.math.cos(theta)
                val b = 1.0 - (rpm / 7000.0) // Backspin braking factor (using 7000 divisor from Claude's example)
                val rollFeet = ((vLand * vLand) / (2.0 * g * mu)) * b
                val rollYards = rollFeet / 3.0

                // Phase 3 - Azimuth correction
                val downrangeCarry = carryYards * kotlin.math.cos(phi)
                val lateralOffset = carryYards * kotlin.math.sin(phi) // This is the offline amount

                // Apply azimuth to roll as well (assuming the ball rolls in the same direction it was hit)
                val downrangeRoll = rollYards * kotlin.math.cos(phi)
                val lateralRoll = rollYards * kotlin.math.sin(phi)

                val totalDownrange = downrangeCarry + downrangeRoll
                val totalLateral = lateralOffset + lateralRoll
                
                // Total distance = √(Downrange² + Lateral²)
                val calculatedTotal = kotlin.math.sqrt((totalDownrange * totalDownrange) + (totalLateral * totalLateral))

                // Lateral offset is our offline distance (combining carry offline and roll offline)
                offlineYards = totalLateral

                val offlineString = formatOffline(offlineYards)

                return ShotData(
                    carryDistance = carryYards,
                    totalDistance = calculatedTotal,
                    offline = offlineString
                )
            } else if (nmeaString.startsWith("#WECO")) {
                // This appears to be a raw hex/binary encoded message that the GC2 sends when a shot is hit.
                // It ends with !END.
                // Example: #WECO00030aX00X00X00WWX0lWWX0l0000096e!END
                Log.d(TAG, "Received WECO shot data message: $nmeaString")
                // TODO: Need to decode the WECO message format.
                // For now, we return null so it doesn't crash, but we need to figure out the encoding.
                return null
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
        if (kotlin.math.abs(deviation) < 0.1) return "0.0"
        
        val direction = if (deviation > 0) "R" else "L"
        return "${String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(deviation))}$direction"
    }
}
