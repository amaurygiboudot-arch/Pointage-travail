package com.amaury.pointage

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import java.util.UUID

/**
 * Autosaves immutable campaign snapshots. Firestore can queue these writes while offline,
 * and parent IDs make concurrent branches detectable without relying on last-write-wins.
 */
class ObjectiveDeliveryCloudSync(
    private val store: ObjectiveDeliveryCampaignStore,
    private val onStatus: (String) -> Unit,
    private val onCampaignRestored: (ObjectiveDeliveryCampaign) -> Unit,
    private val onConflict: (ObjectiveDeliveryCampaign?, List<ObjectiveDeliveryCloudSnapshot>) -> Unit
) {
    private var registration: ListenerRegistration? = null
    private var activeModel: ObjectiveDeliveryCompanyModel? = null
    private var lastConflictKey: String? = null

    fun observe(model: ObjectiveDeliveryCompanyModel) {
        stop()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            onStatus("Sauvegarde automatique sur cet appareil. Connecte Google pour la copie Firebase.")
            return
        }
        activeModel = model
        onStatus("Partie enregistrée sur cet appareil • vérification de la copie Firebase…")
        registration = snapshotsCollection(uid, model)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onStatus("Partie enregistrée sur cet appareil • synchronisation Firebase en attente")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
                    onStatus("Partie enregistrée sur cet appareil • synchronisation Firebase en attente")
                    return@addSnapshotListener
                }
                processSnapshot(uid, model, snapshot)
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
        activeModel = null
    }

    /** Called only after the local checkpoint has been safely committed. */
    fun appendAfterLocalSave(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return campaign
        val parentIds = if (campaign.cloudOwnerUid == uid) {
            listOfNotNull(campaign.cloudHeadSnapshotId).take(8)
        } else {
            emptyList()
        }
        return appendSnapshot(uid, campaign, parentIds)
    }

    fun resolveConflict(
        model: ObjectiveDeliveryCompanyModel,
        local: ObjectiveDeliveryCampaign?,
        cloudTips: List<ObjectiveDeliveryCloudSnapshot>,
        chooseCloud: Boolean,
        preferredCloudSnapshotId: String? = null
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (cloudTips.isEmpty() && local == null) return
        if (activeModel != model) return

        val selectedCloud = cloudTips.firstOrNull { it.id == preferredCloudSnapshotId }
            ?: cloudTips.maxByOrNull { it.campaign.revision }
        val selected = if (chooseCloud) selectedCloud?.campaign else local
        if (selected == null) return

        if (chooseCloud && local != null && local != selected) {
            store.saveRecoveryCopy(local)
        }

        val localBranchId = if (chooseCloud && local != null && local != selected) {
            val localParents = if (local.cloudOwnerUid == uid) {
                listOfNotNull(local.cloudHeadSnapshotId)
            } else {
                emptyList()
            }
            appendSnapshot(uid, local, localParents).cloudHeadSnapshotId
        } else {
            null
        }

        val parentIds = (cloudTips.map { it.id } +
            listOfNotNull(localBranchId) +
            if (local?.cloudOwnerUid == uid) listOfNotNull(local.cloudHeadSnapshotId) else emptyList())
            .distinct()
            .take(8)
        val nextRevision = maxOf(
            selected.revision,
            local?.revision ?: 0L,
            cloudTips.maxOfOrNull { it.campaign.revision } ?: 0L
        ) + 1L
        val resolution = selected.copy(
            revision = nextRevision,
            savedAtEpochMillis = System.currentTimeMillis(),
            cloudOwnerUid = uid,
            cloudHeadSnapshotId = null,
            cloudParentSnapshotIds = emptyList()
        )
        val saved = appendSnapshot(uid, resolution, parentIds)
        store.saveExact(saved)
        lastConflictKey = null
        onCampaignRestored(saved)
        onStatus("Versions réunies • nouvelle copie Firebase enregistrée")
    }

    private fun processSnapshot(
        uid: String,
        model: ObjectiveDeliveryCompanyModel,
        query: QuerySnapshot
    ) {
        val snapshots = query.documents.mapNotNull { document ->
            val payload = document.getString("payload") ?: return@mapNotNull null
            val campaign = ObjectiveDeliveryCampaignCodec.decode(payload) ?: return@mapNotNull null
            val revision = document.getLong("revision") ?: return@mapNotNull null
            val campaignId = document.getString("campaignId") ?: return@mapNotNull null
            if (campaignId != model.key || campaign.companyModel != model || campaign.revision != revision) {
                return@mapNotNull null
            }
            val parents = (document.get("parentSnapshotIds") as? List<*>)
                .orEmpty()
                .filterIsInstance<String>()
                .take(8)
            ObjectiveDeliveryCloudSnapshot(document.id, parents, campaign)
        }
        if (snapshots.isEmpty() && query.documents.isNotEmpty()) {
            onStatus("La copie Firebase est illisible • la partie locale est conservée")
            return
        }

        val tips = ObjectiveDeliveryCloudGraph.tips(snapshots)
        val local = store.load(model)
        if (local == null) {
            when (tips.size) {
                0 -> onStatus("Aucune copie Firebase trouvée")
                1 -> restoreFromCloud(tips.single(), model)
                else -> promptConflict(null, tips)
            }
            return
        }

        if (tips.isEmpty()) {
            if (local.cloudOwnerUid == uid && local.cloudHeadSnapshotId != null) {
                onStatus("Copie Firebase en cours • partie locale conservée")
            } else {
                onStatus("Création de la copie Firebase…")
                val uploaded = appendAfterLocalSave(local)
                store.saveExact(uploaded)
            }
            return
        }

        if (ObjectiveDeliveryGameRules.isPristine(local)) {
            if (tips.size == 1) restoreFromCloud(tips.single(), model, local)
            else promptConflict(null, tips)
            return
        }

        val localHeadId = local.cloudHeadSnapshotId
        if (local.cloudOwnerUid != uid || localHeadId == null) {
            promptConflict(local, tips)
            return
        }
        val localHead = snapshots.firstOrNull { it.id == localHeadId }
        if (localHead == null) {
            onStatus("Synchronisation Firebase en attente • partie locale conservée")
            return
        }
        if (tips.size == 1) {
            val remoteTip = tips.single()
            val localIsAncestor = ObjectiveDeliveryCloudGraph.isAncestorOrSelf(
                localHeadId,
                remoteTip.id,
                snapshots
            )
            if (localIsAncestor) {
                if (remoteTip.id == localHeadId && local.revision > localHead.campaign.revision) {
                    onStatus("Envoi de la dernière décision vers Firebase…")
                    val uploaded = appendAfterLocalSave(local)
                    store.saveExact(uploaded)
                } else if (remoteTip.id != localHeadId && local.revision > localHead.campaign.revision) {
                    promptConflict(local, tips)
                } else if (remoteTip.campaign.revision > local.revision) {
                    restoreFromCloud(remoteTip, model, local)
                } else {
                    onStatus("Partie enregistrée sur cet appareil et synchronisée avec Firebase")
                }
                return
            }
            if (ObjectiveDeliveryCloudGraph.isAncestorOrSelf(remoteTip.id, localHeadId, snapshots)) {
                onStatus("Partie enregistrée sur cet appareil et synchronisée avec Firebase")
                return
            }
        }
        promptConflict(local, tips)
    }

    private fun restoreFromCloud(
        snapshot: ObjectiveDeliveryCloudSnapshot,
        model: ObjectiveDeliveryCompanyModel,
        local: ObjectiveDeliveryCampaign? = null
    ) {
        if (local != null &&
            local != snapshot.campaign &&
            !ObjectiveDeliveryGameRules.isPristine(local)
        ) {
            store.saveRecoveryCopy(local)
        }
        val restored = snapshot.campaign.copy(
            cloudOwnerUid = FirebaseAuth.getInstance().currentUser?.uid,
            cloudHeadSnapshotId = snapshot.id,
            cloudParentSnapshotIds = snapshot.parentIds
        )
        store.saveExact(restored)
        lastConflictKey = null
        onCampaignRestored(restored)
        onStatus("Copie Firebase restaurée pour ${model.label}")
    }

    private fun promptConflict(
        local: ObjectiveDeliveryCampaign?,
        tips: List<ObjectiveDeliveryCloudSnapshot>
    ) {
        val key = (listOf(local?.revision?.toString().orEmpty()) + tips.map { "${it.id}:${it.campaign.revision}" })
            .sorted()
            .joinToString("|")
        onStatus("Deux versions de la partie existent • choisis celle à continuer")
        if (key == lastConflictKey) return
        lastConflictKey = key
        onConflict(local, tips)
    }

    private fun appendSnapshot(
        uid: String,
        campaign: ObjectiveDeliveryCampaign,
        parentIds: List<String>
    ): ObjectiveDeliveryCampaign {
        val model = campaign.companyModel
        val id = "${store.deviceId()}_${UUID.randomUUID().toString().replace("-", "")}"
        val parents = parentIds.distinct().take(8)
        val saved = campaign.copy(
            cloudOwnerUid = uid,
            cloudHeadSnapshotId = id,
            cloudParentSnapshotIds = parents
        )
        val ref = snapshotsCollection(uid, model).document(id)
        ref.set(
            mapOf(
                "schemaVersion" to 1L,
                "campaignId" to model.key,
                "revision" to saved.revision,
                "parentSnapshotIds" to parents,
                "deviceId" to store.deviceId(),
                "payload" to ObjectiveDeliveryCampaignCodec.encode(saved),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            if (activeModel == model) onStatus("Partie enregistrée sur cet appareil et synchronisée avec Firebase")
        }.addOnFailureListener {
            val current = store.load(model)
            if (current?.cloudHeadSnapshotId == id) {
                val localOnly = current.copy(
                    cloudOwnerUid = null,
                    cloudHeadSnapshotId = null,
                    cloudParentSnapshotIds = emptyList()
                )
                store.saveExact(localOnly)
                if (activeModel == model) onCampaignRestored(localOnly)
            }
            if (activeModel == model) onStatus("Partie enregistrée sur cet appareil • synchronisation Firebase en attente")
        }
        return saved
    }

    private fun snapshotsCollection(uid: String, model: ObjectiveDeliveryCompanyModel) =
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("game_saves")
            .document(model.key)
            .collection("snapshots")
}
