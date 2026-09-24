package com.amaury.pointage.v2.ui

import android.content.Context
import com.amaury.pointage.v2.engine.BrightStarV2

data class ConstellationPathV2(
    val abbreviation: String,
    val hrNumbers: IntArray
)

data class StarSkyCatalogV2(
    val stars: List<Pair<Int, BrightStarV2>>,
    val constellationPaths: List<ConstellationPathV2>
)

/**
 * Catalogue local uniquement.
 *
 * Les coordonnées BSC5P et les tracés sont embarqués dans l'APK : aucun GPS,
 * identifiant utilisateur ou requête réseau n'est envoyé à une source
 * astronomique pour dessiner le ciel.
 */
object StarSkyCatalogLoaderV2 {
    @Volatile private var cached: StarSkyCatalogV2? = null

    fun load(context: Context): StarSkyCatalogV2 {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: parse(context.applicationContext).also { cached = it }
        }
    }

    private fun parse(context: Context): StarSkyCatalogV2 {
        val stars = context.assets.open("celestial/bsc5p_v2.tsv").bufferedReader().useLines { lines ->
            lines.filterNot { it.isBlank() || it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 4) return@mapNotNull null
                    val hr = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val ra = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                    val dec = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                    val magnitude = parts[3].toDoubleOrNull() ?: return@mapNotNull null
                    hr to BrightStarV2(
                        id = "HR$hr",
                        rightAscensionJ2000Deg = ra,
                        declinationJ2000Deg = dec,
                        visualMagnitude = magnitude
                    )
                }
                .toList()
        }

        val paths = context.assets.open("celestial/constellation_lines_v2.txt").bufferedReader().useLines { lines ->
            lines.filterNot { it.isBlank() || it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
                    val abbreviation = line.substring(0, separator).trim()
                    val hrs = line.substring(separator + 1)
                        .split(',')
                        .mapNotNull(String::toIntOrNull)
                        .toIntArray()
                    if (abbreviation.isBlank() || hrs.size < 2) null
                    else ConstellationPathV2(abbreviation, hrs)
                }
                .toList()
        }

        require(stars.isNotEmpty()) { "Catalogue BSC5P embarqué vide" }
        require(paths.isNotEmpty()) { "Tracés de constellations embarqués vides" }
        return StarSkyCatalogV2(stars = stars, constellationPaths = paths)
    }
}
