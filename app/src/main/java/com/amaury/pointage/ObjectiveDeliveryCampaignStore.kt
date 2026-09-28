package com.amaury.pointage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** App-private checkpoint store. A committed decision is durable before cloud work begins. */
class ObjectiveDeliveryCampaignStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "objective_delivery").apply { mkdirs() }
    private val recoveryDirectory = File(directory, "recovery").apply { mkdirs() }
    private val currentModelFile = File(directory, "current_model.txt")

    fun currentModel(): ObjectiveDeliveryCompanyModel? =
        ObjectiveDeliveryCompanyModel.fromKey(currentModelFile.readTextOrNull())

    fun deviceId(): String {
        val file = File(directory, "device_id.txt")
        val saved = file.readTextOrNull()?.trim()?.takeIf { it.length in 16..64 }
        if (saved != null) return saved
        val created = UUID.randomUUID().toString().replace("-", "")
        writeAtomically(file, created)
        return created
    }

    fun setCurrentModel(model: ObjectiveDeliveryCompanyModel) {
        writeAtomically(currentModelFile, model.key)
    }

    fun load(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign? {
        val file = campaignFile(model)
        if (!file.exists() || !file.isFile) return null
        val payload = file.readTextOrNull()
        val campaign = payload?.let(ObjectiveDeliveryCampaignCodec::decode)
        if (campaign == null || campaign.companyModel != model) {
            preserveUnreadableSave(file, model)
            return null
        }
        return campaign
    }

    fun save(next: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val stamped = ObjectiveDeliveryGameRules.stampForSave(
            next = next,
            previous = load(next.companyModel),
            nowEpochMillis = System.currentTimeMillis()
        )
        saveExact(stamped)
        setCurrentModel(stamped.companyModel)
        return stamped
    }

    /** Persists an already versioned cloud snapshot without creating another local revision. */
    fun saveExact(campaign: ObjectiveDeliveryCampaign) {
        writeAtomically(campaignFile(campaign.companyModel), ObjectiveDeliveryCampaignCodec.encode(campaign))
        setCurrentModel(campaign.companyModel)
    }

    /** Keeps a recoverable copy before choosing between divergent local and cloud branches. */
    fun saveRecoveryCopy(campaign: ObjectiveDeliveryCampaign): File {
        val target = File(
            recoveryDirectory,
            "${campaign.companyModel.key}_${campaign.revision}_${System.currentTimeMillis()}_${UUID.randomUUID()}.json"
        )
        writeAtomically(target, ObjectiveDeliveryCampaignCodec.encode(campaign))
        return target
    }

    fun recoveryCopies(model: ObjectiveDeliveryCompanyModel): List<ObjectiveDeliveryCampaign> =
        recoveryDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("${model.key}_") && it.extension == "json" }
            .mapNotNull { it.readTextOrNull()?.let(ObjectiveDeliveryCampaignCodec::decode) }
            .filter { it.companyModel == model }
            .sortedByDescending { it.savedAtEpochMillis }

    private fun campaignFile(model: ObjectiveDeliveryCompanyModel) = File(directory, "${model.key}.json")

    /** Preserve a corrupt or incompatible save before a later game start writes over it. */
    private fun preserveUnreadableSave(file: File, model: ObjectiveDeliveryCompanyModel) {
        val recovery = File(
            recoveryDirectory,
            "${model.key}_unreadable_${System.currentTimeMillis()}_${UUID.randomUUID()}.corrupt"
        )
        if (!file.renameTo(recovery)) {
            runCatching { file.copyTo(recovery, overwrite = false) }
                .getOrElse { throw IllegalStateException("Impossible de protéger la sauvegarde illisible", it) }
        }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { if (exists() && isFile) readText(Charsets.UTF_8) else null }.getOrNull()

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Impossible de créer le dossier de sauvegarde") }
        val temp = File(target.parentFile, "${target.name}.tmp")
        val backup = File(target.parentFile, "${target.name}.bak")
        FileOutputStream(temp, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) error("Impossible de libérer le fichier de secours")
        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(backup)) error("Impossible de protéger l’ancienne sauvegarde")
        if (!temp.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            error("Impossible d’enregistrer la sauvegarde locale")
        }
        if (backup.exists()) backup.delete()
    }
}
