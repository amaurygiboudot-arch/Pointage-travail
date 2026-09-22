package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2

enum class GpsPointTypeV2 { POSTE, PARKING, OTHER }
enum class GpsTransitionV2 { ENTER, EXIT }

data class GpsEventV2(
    val id:String,
    val atMs:Long,
    val placeId:String,
    val pointType:GpsPointTypeV2,
    val transition:GpsTransitionV2,
    val employerId:String?=null,
    val accuracyMeters:Float?=null
)

data class GpsDecisionV2(val accepted:Boolean,val duplicate:Boolean,val requiresConfirmation:Boolean,val reason:String)

/** Le GPS produit des événements; il ne pointe jamais directement. */
class GpsEngineV2(private val debounceMs:Long = 30_000L) {
    private data class LastAccepted(val atMs: Long, val transition: GpsTransitionV2)

    private val lastByKey = mutableMapOf<String, LastAccepted>()

    fun ingest(event:GpsEventV2):GpsDecisionV2 {
        require(event.atMs > 0L)
        val key = "${event.placeId}:${event.pointType}"
        val previous = lastByKey[key]
        if (previous != null &&
            previous.transition == event.transition &&
            event.atMs - previous.atMs in 0 until debounceMs
        ) {
            return GpsDecisionV2(false,true,false,"Événement GPS ignoré par anti-rebond")
        }
        // La transition opposée prouve un nouveau franchissement et réarme l'anti-rebond.
        lastByKey[key] = LastAccepted(event.atMs, event.transition)
        val ambiguous = event.pointType == GpsPointTypeV2.PARKING || event.pointType == GpsPointTypeV2.OTHER
        return GpsDecisionV2(true,false,ambiguous,if(ambiguous) "Événement à qualifier" else "Événement GPS valide")
    }

    fun reset() = lastByKey.clear()
}
