package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amaury.pointage.BackupSecurityPolicy
import com.amaury.pointage.DriveBackupManager
import com.amaury.pointage.GpsPresenceStateKeysV2
import com.amaury.pointage.GeofenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Sauvegarde des données fonctionnelles HoraTrack, sans jetons d'authentification. */
object V2BackupManager {
    private const val FORMAT_VERSION = 4
    private const val ROOT_FOLDER = "Pointage Travail"
    private const val FILE_NAME = "HoraTrack_backup.json"
    private const val LEGACY_FILE_NAME = "HoraTrack_V2_backup.json"
    private const val RUNTIME_PREFS = "horatrack_v2_test_runtime"
    private const val SALARY_COMPANIES_PREFS = "salary_companies_v2"
    private const val SALARY_COMPANY_PREFIX = "salary_company_"
    private val executor = Executors.newSingleThreadExecutor()

    private val basePreferenceFiles = listOf(
        RUNTIME_PREFS,
        "horatrack_v2_integration",
        "horatrack_v2_migration",
        "horatrack_v2_legal_sources",
        "horatrack_v2_rights",
        "horatrack_v2_payslips",
        PayrollCoverageAttestationStoreV2.PREFS,
        "horatrack_v2_company_pause",
        "horatrack_v2_gps_state",
        SALARY_COMPANIES_PREFS,
        "salary_settings",
        "gps_settings",
        "shift_profiles",
        "appearance_settings",
        "widget_style",
        "place_names",
        "smart_setup",
        "welcome_preview"
    )

    data class RestoreResult(val restoredFiles:Int,val mergedSessions:Int)
    internal data class HistoryMergePlan(val history:JSONArray,val added:Int)

    fun backupIfConfiguredAsync(context:Context){ val app=context.applicationContext;if(DriveBackupManager.savedTreeUri(app)==null)return;executor.execute{backupToConfiguredDrive(app)} }
    fun restoreFreshInstallIfConfiguredAsync(context:Context){ val app=context.applicationContext;if(DriveBackupManager.savedTreeUri(app)==null||!isFreshInstall(app))return;executor.execute{runCatching{val uri=configuredBackupUri(app)?:return@runCatching;restoreFromUri(app,uri).getOrThrow()}} }
    fun backupToConfiguredDrive(context:Context):Result<Uri> = runCatching { DriveBackupManager.withStorageAccess { val tree=DriveBackupManager.savedTreeUri(context)?:error("Choisis d'abord un dossier Google Drive");val root=treeRootDocumentUri(tree);val folder=ensureDirectory(context,root,ROOT_FOLDER);val file=ensureFile(context,folder,FILE_NAME,"application/json");context.contentResolver.openOutputStream(file,"w")?.bufferedWriter(Charsets.UTF_8)?.use{it.write(snapshot(context).toString(2))}?:error("Impossible d'écrire la sauvegarde");context.getSharedPreferences("horatrack_v2_backup",Context.MODE_PRIVATE).edit().putLong("last_backup_ms",System.currentTimeMillis()).apply();file } }
    fun restoreFromUri(context:Context,uri:Uri):Result<RestoreResult> = runCatching { val raw=DriveBackupManager.withStorageAccess { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:error("Impossible de lire la sauvegarde") };restoreFromJson(context,raw).getOrThrow() }

    /** Même restauration conservatrice pour Drive et Firestore. */
    fun restoreFromJson(context:Context,raw:String):Result<RestoreResult> = runCatching {
        val root=JSONObject(raw)
        require(isSupportedFormatVersion(root.optInt("formatVersion",0))){"Format de sauvegarde non reconnu"}
        val files=root.optJSONObject("preferences")?:error("Sauvegarde incomplète")
        val savedNames=files.keys().asSequence().filter(::isManagedPreferenceFileName).filter(BackupSecurityPolicy::canTransferPreferenceFile).toList()
            .sortedWith(compareBy<String> { if (it == RUNTIME_PREFS) 0 else if (it == SALARY_COMPANIES_PREFS) 1 else if (it.startsWith(SALARY_COMPANY_PREFIX)) 2 else 3 }.thenBy { it })
        require(savedNames.isNotEmpty()){"Sauvegarde vide ou sans données reconnues"}
        val payloads=savedNames.associateWith{name->
            val saved=files.optJSONObject(name)?:error("Préférences $name illisibles")
            require(isValidTypedPreferencePayload(saved)){"Préférences $name invalides"}
            saved
        }
        val runtimePlan=payloads[RUNTIME_PREFS]?.let{prepareRuntimeMerge(context,it)}
        clearEphemeralGpsPresenceState(context)
        var restored=0;var merged=0
        savedNames.forEach{name->
            val saved=payloads.getValue(name)
            if(name==RUNTIME_PREFS){
                val plan=runtimePlan?:error("Historique de sauvegarde indisponible")
                merged=applyRuntimeMerge(context,plan)
            }else mergePreferences(context,name,saved)
            restored++
        }
        V2ProfileStore.bind(context)
        V2MigrationManager.ensureMigrated(context)
        GeofenceManager.reconfigureStoredZones(context)
        RestoreResult(restored,merged)
    }

    /** Importe un ancien cloud directement dans le moteur actuel, sans toucher au stockage legacy local. */
    fun importLegacyPointageJson(context:Context,legacy:JSONArray):Int =
        V2MigrationManager.importLegacyArray(context.applicationContext, legacy).imported

    fun snapshot(context:Context):JSONObject {
        V2RuntimeReader.allSessions(context).requireReliable()
        val all=JSONObject()
        transferablePreferenceFiles(context).forEach{all.put(it,encodePreferences(context,it))}
        return JSONObject().put("formatVersion",FORMAT_VERSION).put("schemaVersion",HoraTrackV2.SCHEMA_VERSION).put("createdAtMs",System.currentTimeMillis()).put("preferences",all)
    }

    internal fun isSupportedFormatVersion(version:Int):Boolean = version in 1..FORMAT_VERSION

    /** Noms que ce format de sauvegarde est autorisé à restaurer. */
    internal fun isManagedPreferenceFileName(name:String):Boolean =
        name in basePreferenceFiles || name.startsWith(SALARY_COMPANY_PREFIX)

    private fun transferablePreferenceFiles(context:Context):List<String> {
        val dynamicSalaryFiles = sharedPreferenceFileNames(context).filter { it.startsWith(SALARY_COMPANY_PREFIX) }
        return (basePreferenceFiles + dynamicSalaryFiles)
            .distinct()
            .filter(::isManagedPreferenceFileName)
            .filter(BackupSecurityPolicy::canTransferPreferenceFile)
            .sorted()
    }

    private fun sharedPreferenceFileNames(context:Context):List<String> {
        val dir=File(context.applicationInfo.dataDir,"shared_prefs")
        return dir.listFiles().orEmpty()
            .filter{it.isFile&&it.name.endsWith(".xml")}
            .map{it.name.removeSuffix(".xml")}
    }

    private fun isFreshInstall(context:Context):Boolean {
        val runtime=context.getSharedPreferences(RUNTIME_PREFS,Context.MODE_PRIVATE)
        val history=V2RuntimeHistoryGuardV2.read(context)
        if(!history.reliable)return false
        val runtimeEntry=runtime.all["real_entry"]
        if(runtime.contains("real_entry")&&strictLong(runtimeEntry)==null)return false
        val legacyPrefs=context.getSharedPreferences("pointage",Context.MODE_PRIVATE)
        val legacy=if(!legacyPrefs.contains("data"))JSONArray() else {
            val raw=runCatching{legacyPrefs.getString("data",null)}.getOrNull()?:return false
            runCatching{JSONArray(raw)}.getOrNull()?:return false
        }
        val salary=context.getSharedPreferences("salary_settings",Context.MODE_PRIVATE)
        val salaryV2=context.getSharedPreferences(SALARY_COMPANIES_PREFS,Context.MODE_PRIVATE)
        val hasRuntime=(strictLong(runtimeEntry)?:0L)>0L||history.history.length()>0
        val hasLegacy=legacy.length()>0
        return !hasRuntime&&!hasLegacy&&salary.all.isEmpty()&&salaryV2.all.isEmpty()
    }
    private fun configuredBackupUri(context:Context):Uri? = DriveBackupManager.withStorageAccess { val tree=DriveBackupManager.savedTreeUri(context)?:return@withStorageAccess null;val root=treeRootDocumentUri(tree);val folder=findChild(context,root,ROOT_FOLDER,DocumentsContract.Document.MIME_TYPE_DIR)?:return@withStorageAccess null;findChild(context,folder,FILE_NAME,"application/json")?:findChild(context,folder,LEGACY_FILE_NAME,"application/json") }
    private fun encodePreferences(context:Context,name:String):JSONObject { val out=JSONObject();context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach{(k,v)->if(GpsPresenceStateKeysV2.isTransferablePreferenceKey(name,k))when(v){is String->out.put(k,JSONObject().put("t","s").put("v",v));is Boolean->out.put(k,JSONObject().put("t","b").put("v",v));is Int->out.put(k,JSONObject().put("t","i").put("v",v));is Long->out.put(k,JSONObject().put("t","l").put("v",v));is Float->out.put(k,JSONObject().put("t","f").put("v",v.toDouble()));is Set<*>->out.put(k,JSONObject().put("t","set").put("v",JSONArray(v.filterIsInstance<String>())))}};return out }
    private fun mergePreferences(context:Context,name:String,saved:JSONObject){
        require(isValidTypedPreferencePayload(saved)){"Préférences $name invalides"}
        val editor=context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE).edit()
        if(name=="gps_settings")GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach{editor.remove(it)}
        val keys=saved.keys()
        while(keys.hasNext()){
            val key=keys.next();val item=saved.getJSONObject(key);val value=item.get("v")
            if(!GpsPresenceStateKeysV2.isTransferablePreferenceKey(name,key))continue
            when(item.getString("t")){
                "s"->editor.putString(key,value as String)
                "b"->editor.putBoolean(key,value as Boolean)
                "i"->editor.putInt(key,strictLong(value)?.toInt()?:error("Entier invalide pour $key"))
                "l"->editor.putLong(key,strictLong(value)?:error("Long invalide pour $key"))
                "f"->editor.putFloat(key,(value as Number).toFloat())
                "set"->{val array=value as JSONArray;val set=buildSet{for(index in 0 until array.length())add(array.getString(index))};editor.putStringSet(key,set)}
            }
        }
        check(editor.commit()){ "Échec d'écriture de $name" }
    }

    private fun clearEphemeralGpsPresenceState(context: Context) {
        val editor = context.applicationContext
            .getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
            .edit()
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { editor.remove(it) }
        check(editor.commit()) { "Impossible de réinitialiser l'état de présence GPS" }
    }

    internal fun isValidTypedPreferencePayload(saved:JSONObject):Boolean {
        val keys=saved.keys()
        while(keys.hasNext()){
            val key=keys.next();val item=saved.optJSONObject(key)?:return false
            if(!item.has("v")||item.isNull("v"))return false
            val value=item.opt("v")
            val valid=when(item.optString("t")){
                "s"->value is String
                "b"->value is Boolean
                "i"->strictLong(value)?.let{it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()}==true
                "l"->strictLong(value)!=null
                "f"->(value as? Number)?.toDouble()?.isFinite()==true
                "set"->{val array=value as? JSONArray?:return false;(0 until array.length()).all{array.opt(it) is String}}
                else->false
            }
            if(!valid)return false
        }
        return true
    }

    internal fun decodeBackupHistory(saved:JSONObject):JSONArray {
        if(!saved.has("history"))return JSONArray()
        val item=saved.optJSONObject("history")?:error("Historique de sauvegarde mal typé")
        val raw=(item.opt("v") as? String)?.takeIf{item.optString("t")=="s"}
            ?:error("Historique de sauvegarde mal typé")
        val decoded=V2RuntimeHistoryGuardV2.decode(raw)
        require(decoded.reliable){"Historique de sauvegarde illisible ou incohérent"}
        return JSONArray(decoded.history.toString())
    }

    internal fun mergeHistories(current:JSONArray,saved:JSONArray):HistoryMergePlan {
        require(V2RuntimeHistoryGuardV2.inspect(current).reliable){"Historique local illisible ou incohérent"}
        require(V2RuntimeHistoryGuardV2.inspect(saved).reliable){"Historique de sauvegarde illisible ou incohérent"}
        val merged=JSONArray(current.toString())
        val localById=mutableMapOf<String,JSONObject>()
        for(index in 0 until merged.length()){
            val item=merged.getJSONObject(index)
            localById[item.getString("id")]=item
        }
        var added=0
        for(index in 0 until saved.length()){
            val item=saved.getJSONObject(index);val id=item.getString("id")
            val local=localById[id]
            if(local==null){
                val copy=JSONObject(item.toString())
                merged.put(copy);localById[id]=copy;added++
            }else require(canonicalJson(local)==canonicalJson(item)){
                "La session $id diffère entre le téléphone et la sauvegarde"
            }
        }
        require(V2RuntimeHistoryGuardV2.inspect(merged).reliable){"Fusion d'historique incohérente"}
        return HistoryMergePlan(merged,added)
    }

    private fun canonicalJson(value:Any?):String=when(value){
        is JSONObject->value.keys().asSequence().toList().sorted()
            .joinToString(prefix="{",postfix="}"){key->JSONObject.quote(key)+":"+canonicalJson(value.get(key))}
        is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){index->canonicalJson(value.get(index))}
        JSONObject.NULL,null->"null"
        is String->JSONObject.quote(value)
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        else->value.toString()
    }

    private fun prepareRuntimeMerge(context:Context,saved:JSONObject):HistoryMergePlan {
        V2RuntimeReader.allSessions(context).requireReliable()
        val current=V2RuntimeHistoryGuardV2.read(context)
        require(current.reliable){"Historique local illisible : restauration bloquée"}
        return mergeHistories(current.history,decodeBackupHistory(saved))
    }

    private fun applyRuntimeMerge(context:Context,plan:HistoryMergePlan):Int {
        if(plan.added==0)return 0
        check(V2RuntimeHistoryGuardV2.save(context,plan.history)){"Impossible d'enregistrer l'historique fusionné"}
        return plan.added
    }
    private fun strictLong(value:Any?):Long?=when(value){
        is Byte,is Short,is Int,is Long->(value as Number).toLong()
        is Float,is Double->{val number=(value as Number).toDouble();number.takeIf{it.isFinite()&&it%1.0==0.0}?.toLong()}
        is String->value.trim().toLongOrNull()
        else->null
    }
    private fun treeRootDocumentUri(u:Uri):Uri=DocumentsContract.buildDocumentUriUsingTree(u,DocumentsContract.getTreeDocumentId(u))
    private fun ensureDirectory(c:Context,p:Uri,n:String):Uri=findChild(c,p,n,DocumentsContract.Document.MIME_TYPE_DIR)?:DocumentsContract.createDocument(c.contentResolver,p,DocumentsContract.Document.MIME_TYPE_DIR,n)?:error("Impossible de créer $n")
    private fun ensureFile(c:Context,p:Uri,n:String,m:String):Uri=findChild(c,p,n,m)?:DocumentsContract.createDocument(c.contentResolver,p,m,n)?:error("Impossible de créer $n")
    private fun findChild(c:Context,p:Uri,n:String,m:String):Uri?{val id=DocumentsContract.getDocumentId(p);val children=DocumentsContract.buildChildDocumentsUriUsingTree(p,id);val projection=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE);c.contentResolver.query(children,projection,null,null,null)?.use{cur->val ci=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val cn=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);val cm=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);while(cur.moveToNext())if(cur.getString(cn)==n&&cur.getString(cm)==m)return DocumentsContract.buildDocumentUriUsingTree(p,cur.getString(ci))};return null}
}
