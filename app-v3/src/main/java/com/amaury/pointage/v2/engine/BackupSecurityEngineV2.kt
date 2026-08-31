package com.amaury.pointage.v2.engine

import java.security.MessageDigest

data class BackupManifestV2(val schemaVersion:Int,val createdAtMs:Long,val accountIdHash:String,val payloadHash:String,val itemCount:Int)
data class BackupEnvelopeV2(val manifest:BackupManifestV2,val payload:String)

object BackupEngineV2 {
    fun create(schemaVersion:Int,createdAtMs:Long,accountStableId:String,payload:String,itemCount:Int):BackupEnvelopeV2 {
        require(accountStableId.isNotBlank())
        return BackupEnvelopeV2(BackupManifestV2(schemaVersion,createdAtMs,sha256(accountStableId),sha256(payload),itemCount),payload)
    }
    fun verify(envelope:BackupEnvelopeV2):Boolean = envelope.manifest.payloadHash==sha256(envelope.payload)
    private fun sha256(value:String):String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

data class SecurityPolicyV2(val biometricEnabled:Boolean,val pinEnabled:Boolean,val autoLockMinutes:Int,val integrityChecksEnabled:Boolean=true)
data class SecurityStateV2(val locked:Boolean,val anomalyDetected:Boolean,val message:String?)
object SecurityEngineV2 {
    fun validate(policy:SecurityPolicyV2):List<String> = buildList {
        if(policy.autoLockMinutes<0) add("Délai de verrouillage invalide")
        if(!policy.biometricEnabled && !policy.pinEnabled) add("Aucun verrou d'interface configuré")
    }
    fun onIntegrityCheck(ok:Boolean)=if(ok) SecurityStateV2(false,false,null) else SecurityStateV2(true,true,"Anomalie d'intégrité détectée — données préservées")
}
