package com.amaury.pointage

/** A private immutable cloud checkpoint and its parent checkpoints. */
data class ObjectiveDeliveryCloudSnapshot(
    val id: String,
    val parentIds: List<String>,
    val campaign: ObjectiveDeliveryCampaign
)

/** Pure graph helpers used to distinguish a newer copy from two divergent devices. */
object ObjectiveDeliveryCloudGraph {
    fun tips(snapshots: List<ObjectiveDeliveryCloudSnapshot>): List<ObjectiveDeliveryCloudSnapshot> {
        val hasChild = snapshots.flatMap { it.parentIds }.toSet()
        return snapshots.filterNot { it.id in hasChild }
    }

    fun isAncestorOrSelf(
        ancestorId: String,
        descendantId: String,
        snapshots: List<ObjectiveDeliveryCloudSnapshot>
    ): Boolean {
        if (ancestorId == descendantId) return true
        val byId = snapshots.associateBy { it.id }
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(descendantId)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val node = byId[current] ?: continue
            for (parentId in node.parentIds) {
                if (parentId == ancestorId) return true
                pending.add(parentId)
            }
        }
        return false
    }
}
