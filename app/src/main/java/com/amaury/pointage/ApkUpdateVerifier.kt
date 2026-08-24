package com.amaury.pointage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object ApkUpdateVerifier {
    private const val RELEASE_BASE = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download"

    fun verify(context: Context, apk: File, versionName: String) {
        if (!apk.exists()) throw IllegalStateException("fichier APK absent")
        if (apk.length() < 100_000L) throw IllegalStateException("fichier APK incomplet")

        val expectedSha = downloadExpectedSha256(versionName)
        val actualSha = sha256(apk)
        if (!MessageDigest.isEqual(hexToBytes(expectedSha), hexToBytes(actualSha))) {
            throw SecurityException("empreinte SHA-256 de l'APK invalide")
        }

        verifyPackageAndSigningCertificate(context, apk)
    }

    private fun downloadExpectedSha256(versionName: String): String {
        val version = versionName.trim().removePrefix("v")
        if (version.isBlank()) throw IllegalStateException("version de mise à jour absente")
        val url = "$RELEASE_BASE/v$version/SHA256.txt"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "HoraTrack-Android")
            setRequestProperty("Accept", "text/plain")
            connect()
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("SHA-256 de la release indisponible")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return Regex("(?i)\\b[0-9a-f]{64}\\b").find(text)?.value?.lowercase(Locale.ROOT)
                ?: throw IllegalStateException("SHA-256 de la release invalide")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyPackageAndSigningCertificate(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalStateException("Android ne reconnaît pas l'APK")
        if (archive.packageName != context.packageName) {
            throw SecurityException("APK d'une autre application")
        }

        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedDigests = signingDigests(installed)
        val archiveDigests = signingDigests(archive)

        if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
            throw SecurityException("certificat de signature introuvable")
        }
        if (installedDigests.intersect(archiveDigests).isEmpty()) {
            throw SecurityException("certificat de signature différent")
        }
    }

    private fun signingDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            val current = signingInfo.apkContentsSigners?.toList().orEmpty()
            val history = if (signingInfo.hasMultipleSigners()) emptyList() else signingInfo.signingCertificateHistory?.toList().orEmpty()
            (current + history).distinctBy { it.toCharsString() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim().lowercase(Locale.ROOT)
        if (clean.length != 64 || clean.any { it !in "0123456789abcdef" }) {
            throw IllegalArgumentException("empreinte SHA-256 invalide")
        }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
