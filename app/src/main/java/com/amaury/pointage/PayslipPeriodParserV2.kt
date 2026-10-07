package com.amaury.pointage

/** Requires an explicit monthly period label; conflicting or malformed labels remain unknown. */
internal object PayslipPeriodParserV2 {
    data class Period(val year: Int, val monthZeroBased: Int, val sourceLine: String)
    fun parse(text: String): Period? {
        var invalid = false
        val found = text.lineSequence().take(3000).mapNotNull { line ->
            val label = Regex("(?i)^\\s*(?:période|periode|mois)(?:\\s+(?:de\\s+paie|du\\s+bulletin))?\\s*[:=]\\s*(.*)$").find(line)
                ?: return@mapNotNull null
            val match = Regex("^(0?[1-9]|1[0-2])[/.-](20\\d{2})$").matchEntire(label.groupValues[1].trim())
            if (match == null) { invalid = true; return@mapNotNull null }
            Period(match.groupValues[2].toInt(), match.groupValues[1].toInt() - 1, line.trim().take(180))
        }.toList()
        return if (!invalid && found.map { it.year to it.monthZeroBased }.distinct().size == 1) found.first() else null
    }
}
