package com.amaury.pointage

/** Firebase-free contract shared with iOS. Tombstones retain the revision after deletion. */
data class ComfortCloudSnapshotV2(val revision: Long, val comfort: ComfortTransferV2?) {
    val nextRevision: Long get() {
        require(revision in 0 until MAX_REVISION) { "Limite de révision atteinte" }
        return revision + 1
    }
    fun requireExpected(expected: Long) {
        require(revision == expected) { "La sauvegarde a changé sur un autre appareil. Relis-la avant de continuer." }
    }
    companion object {
        const val MAX_REVISION = 1_000_000_000L
        fun decode(data: Map<String, Any>?): ComfortCloudSnapshotV2 {
            if (data == null) return ComfortCloudSnapshotV2(0, null)
            require(data.keys == setOf("schemaVersion", "revision", "payload", "deleted", "updatedAt")) { "Sauvegarde incompatible" }
            val version = data["schemaVersion"] as? Number
            val revision = data["revision"] as? Number
            require(version?.toDouble() == 1.0) { "Version de sauvegarde incompatible" }
            val number = revision?.toDouble() ?: error("Révision manquante")
            require(number.isFinite() && number in 1.0..MAX_REVISION.toDouble() && number == number.toLong().toDouble()) { "Révision invalide" }
            val deleted = data["deleted"] as? Boolean ?: error("État de sauvegarde invalide")
            val payload = data["payload"] as? String ?: error("Contenu manquant")
            val comfort = if (deleted) { require(payload.isEmpty()) { "Suppression incohérente" }; null }
                else ComfortTransferV2.decode(payload)
            return ComfortCloudSnapshotV2(number.toLong(), comfort)
        }
    }
}
