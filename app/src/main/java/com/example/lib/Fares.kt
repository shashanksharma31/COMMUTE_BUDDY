package com.example.lib

import kotlin.math.roundToInt

data class FormattedVerifiedFare(
    val costInr: Int,
    val display: String,
    val isEstimate: Boolean
)

/**
 * Formats verified backend fare envelopes per Truth-Over-Completeness policy.
 */
object Fares {
    fun formatVerifiedFareDisplay(
        totalInr: Int?,
        isEstimate: Boolean = false,
        status: String? = null
    ): FormattedVerifiedFare {
        if (totalInr == null || status == "unavailable") {
            return FormattedVerifiedFare(
                costInr = 0,
                display = "Fare unavailable",
                isEstimate = false
            )
        }
        val rounded = totalInr.coerceAtLeast(0)
        val estimated = isEstimate || status == "estimate"
        return FormattedVerifiedFare(
            costInr = rounded,
            display = if (estimated) "₹$rounded (Estimated)" else "₹$rounded",
            isEstimate = estimated
        )
    }

    fun mapEnvelopeStatusToDataLabel(status: String?): DataSourceLabel {
        return when (status?.lowercase()) {
            "live" -> DataSourceLabel.LIVE
            "scheduled" -> DataSourceLabel.TIMETABLE
            "estimate" -> DataSourceLabel.ESTIMATED
            "stale" -> DataSourceLabel.STALE
            "unverified" -> DataSourceLabel.UNVERIFIED
            "unavailable" -> DataSourceLabel.UNAVAILABLE
            else -> DataSourceLabel.TIMETABLE
        }
    }
}
