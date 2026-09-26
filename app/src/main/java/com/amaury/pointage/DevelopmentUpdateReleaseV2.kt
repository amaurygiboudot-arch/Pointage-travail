package com.amaury.pointage

import org.json.JSONArray

/**
 * Décrit le canal de développement HoraTrack.
 *
 * Une seule prerelease GitHub permanente, `dev-latest`, contient toujours le dernier APK
 * de développement validé par le workflow DEV. Les anciennes prereleases numérotées sont
 * ignorées afin de garder un point d'entrée unique pour l'application et pour les tests manuels.
 *
 * Les assets de `dev-latest` sont remplacés à chaque build. Une révision unique est donc ajoutée
 * aux URL de téléchargement afin d'empêcher un cache HTTP/CDN de mélanger l'APK d'un build avec
 * le SHA-256 du build précédent.
 */
object DevelopmentUpdateReleaseV2 {
    private const val DEVELOPMENT_TAG = "dev-latest"
    private const val REPOSITORY_RELEASE_PREFIX =
        "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/$DEVELOPMENT_TAG/"
    private val versionRegex = Regex("^\\d+(?:\\.\\d+)+-dev-(\\d{9,10})$")

    data class Candidate(
        val versionName: String,
        val revision: String,
        val tag: String,
        val apkUrl: String
    )

    fun isDevelopmentVersion(versionName: String): Boolean =
        versionRegex.matches(versionName.trim())

    fun revision(versionName: String): String? =
        versionRegex.matchEntire(versionName.trim())?.groupValues?.getOrNull(1)

    fun parseLatest(json: String): Candidate? {
        val releases = runCatching { JSONArray(json) }.getOrNull() ?: return null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false) || !release.optBoolean("prerelease", false)) continue

            val tag = release.optString("tag_name").trim()
            if (tag != DEVELOPMENT_TAG) continue

            val versionName = parseVersionFromBody(release.optString("body")) ?: continue
            val versionRevision = revision(versionName) ?: continue

            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (!asset.optString("name").equals("HoraTrack-dev.apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url").trim()
                if (url.startsWith(REPOSITORY_RELEASE_PREFIX)) {
                    apkUrl = cacheBoundUrl(url, versionRevision)
                    break
                }
            }
            if (apkUrl != null) return Candidate(versionName, versionRevision, tag, apkUrl)
        }
        return null
    }

    internal fun sha256Url(versionName: String): String? {
        val buildRevision = revision(versionName) ?: return null
        return cacheBoundUrl("${REPOSITORY_RELEASE_PREFIX}SHA256-dev.txt", buildRevision)
    }

    private fun cacheBoundUrl(url: String, buildRevision: String): String =
        "$url?rev=$buildRevision"

    private fun parseVersionFromBody(body: String): String? = body.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("DEV_VERSION=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf(::isDevelopmentVersion)
}
