package com.amaury.pointage

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object ApkUpdateVerifier {
    private const val RELEASE_BASE = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download"

    class RetryableVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

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

    internal fun expectedSha256Url(versionName: String): String {
        val version = versionName.trim().removePrefix("v")
        if (version.isBlank()) throw IllegalStateException("version de mise à jour absente")
        return DevelopmentUpdateReleaseV2.sha256Url(version)
            ?: "$RELEASE_BASE/v$version/SHA256.txt"
    }

    private fun downloadExpectedSha256(versionName: String): String {
        val url = expectedSha256Url(versionName)
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HoraTrack-Android")
                setRequestProperty("Accept", "text/plain")
                connect()
            }
        } catch (e: IOException) {
            throw RetryableVerificationException("réseau indisponible pendant la vérification", e)
        }
        try {
            val code = try { connection.responseCode } catch (e: IOException) {
                throw RetryableVerificationException("réponse réseau indisponible", e)
            }
            if (code == 403 || code == 408 || code == 429 || code in 500..599) {
                throw RetryableVerificationException("service SHA-256 temporairement indisponible ($code)")
            }
            if (code !in 200..299) throw SecurityException("SHA-256 de la release indisponible ($code)")
            val text = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw RetryableVerificationException("lecture SHA-256 interrompue", e)
            }
            return Regex("(?i)\\b[0-9a-f]{64}\\b").find(text)?.value?.lowercase(Locale.ROOT)
                ?: throw SecurityException("SHA-256 de la release invalide")
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
        if (archive.packageName != context.packageName) throw SecurityException("APK d'une autre application")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (!sameCurrentSignerSet(installed, archive)) {
            throw SecurityException("certificat de signature incompatible")
        }
    }

    private fun sameCurrentSignerSet(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedCurrent = currentSignerDigests(installed)
        val archiveCurrent = currentSignerDigests(archive)
        return installedCurrent.isNotEmpty() && installedCurrent == archiveCurrent
    }

    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signatures.mapTo(linkedSetOf(), ::digestSignature)
    }

    private fun digestSignature(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

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
