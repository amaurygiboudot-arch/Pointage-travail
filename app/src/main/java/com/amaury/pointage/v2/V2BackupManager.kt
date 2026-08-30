package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amaury.pointage.DriveBackupManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Sauvegarde des données fonctionnelles HoraTrack, sans jetons d'authentification. */
object V2BackupManager {
    private const val FORMAT_VERSION = 3
    private const val ROOT_FOLDER = "Pointage Travail"
    private const val FILE_NAME = "HoraTrack_backup.json"
    private const val LEGACY_FILE_NAME = "HoraTrack_V2_backup.json"
    private val executor = Executors.newSingleThreadExecutor()
    private val preferenceFiles = listOf("horatrack_v2_test_runtime","horatrack_v2_integration","horatrack_v2_migration","horatrack_v2_legal_sources","horatrack_v2_rights","horatrack_v2_payslips","horatrack_v2_company_pause","horatrack_v2_gps_state","v2_app_lock","salary_settings","gps_settings","shift_profiles","appearance_settings","widget_style","place_names","smart_setup","welcome_preview")
    data class RestoreResult(val restoredFiles:Int,val mergedSessions:Int)

    fun backupIfConfiguredAsync(context:Context){ val app=context.applicationContext;if(DriveBackupManager.savedTreeUri(app)==null)return;executor.execute{backupToConfiguredDrive(app)} }
    fun restoreFreshInstallIfConfiguredAsync(context:Context){ val app=context.applicationContext;if(DriveBackupManager.savedTreeUri(app)==null||!isFreshInstall(app))return;executor.execute{runCatching{val uri=configuredBackupUri(app)?:return@runCatching;restoreFromUri(app,uri).getOrThrow()}} }
    fun backupToConfiguredDrive(context:Context):Result<Uri> = runCatching { val tree=DriveBackupManager.savedTreeUri(context)?:error("Choisis d'abord un dossier Google Drive");val root=treeRootDocumentUri(tree);val folder=ensureDirectory(context,root,ROOT_FOLDER);val file=ensureFile(context,folder,FILE_NAME,"application/json");context.contentResolver.openOutputStream(file,"w")?.bufferedWriter(Charsets.UTF_8)?.use{it.write(snapshot(context).toString(2))}?:error("Impossible d'écrire la sauvegarde");context.getSharedPreferences("horatrack_v2_backup",Context.MODE_PRIVATE).edit().putLong("last_backup_ms",System.currentTimeMillis()).apply();file }
    fun restoreFromUri(context:Context,uri:Uri):Result<RestoreResult> = runCatching { val raw=context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:error("Impossible de lire la sauvegarde");restoreFromJson(context,raw).getOrThrow() }

    /** Même restauration conservatrice pour Drive et Firestore. */
    fun restoreFromJson(context:Context,raw:String):Result<RestoreResult> = runCatching {
        val root=JSONObject(raw);require(root.optInt("formatVersion",0) in 1..FORMAT_VERSION){"Format de sauvegarde non reconnu"};val files=root.optJSONObject("preferences")?:error("Sauvegarde incomplète")
        var restored=0;var merged=0
        preferenceFiles.forEach{name->val saved=files.optJSONObject(name)?:return@forEach;if(name=="horatrack_v2_test_runtime")merged+=restoreRuntime(context,saved) else mergePreferences(context,name,saved);restored++}
        V2ProfileStore.bind(context);V2MigrationManager.ensureMigrated(context);RestoreResult(restored,merged)
    }

    /** Importe un ancien cloud directement dans le moteur actuel, sans toucher au stockage legacy local. */
    fun importLegacyPointageJson(context:Context,legacy:JSONArray):Int =
        V2MigrationManager.importLegacyArray(context.applicationContext, legacy).imported

    fun snapshot(context:Context):JSONObject { val all=JSONObject();preferenceFiles.forEach{all.put(it,encodePreferences(context,it))};return JSONObject().put("formatVersion",FORMAT_VERSION).put("schemaVersion",HoraTrackV2.SCHEMA_VERSION).put("createdAtMs",System.currentTimeMillis()).put("preferences",all) }
    private fun isFreshInstall(context:Context):Boolean { val runtime=context.getSharedPreferences("horatrack_v2_test_runtime",Context.MODE_PRIVATE);val legacy=context.getSharedPreferences("pointage",Context.MODE_PRIVATE).getString("data","[]").orEmpty();val salary=context.getSharedPreferences("salary_settings",Context.MODE_PRIVATE);val hasRuntime=numeric(runtime.all["real_entry"])>0L||runCatching{JSONArray(runtime.getString("history","[]")?:"[]").length()>0}.getOrDefault(false);val hasLegacy=runCatching{JSONArray(legacy.ifBlank{"[]"}).length()>0}.getOrDefault(false);return !hasRuntime&&!hasLegacy&&salary.all.isEmpty() }
    private fun configuredBackupUri(context:Context):Uri? { val tree=DriveBackupManager.savedTreeUri(context)?:return null;val root=treeRootDocumentUri(tree);val folder=findChild(context,root,ROOT_FOLDER,DocumentsContract.Document.MIME_TYPE_DIR)?:return null;return findChild(context,folder,FILE_NAME,"application/json")?:findChild(context,folder,LEGACY_FILE_NAME,"application/json") }
    private fun encodePreferences(context:Context,name:String):JSONObject { val out=JSONObject();context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach{(k,v)->when(v){is String->out.put(k,JSONObject().put("t","s").put("v",v));is Boolean->out.put(k,JSONObject().put("t","b").put("v",v));is Int->out.put(k,JSONObject().put("t","i").put("v",v));is Long->out.put(k,JSONObject().put("t","l").put("v",v));is Float->out.put(k,JSONObject().put("t","f").put("v",v.toDouble()));is Set<*>->out.put(k,JSONObject().put("t","set").put("v",JSONArray(v.filterIsInstance<String>())))}};return out }
    private fun mergePreferences(context:Context,name:String,saved:JSONObject){val editor=context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE).edit();val keys=saved.keys();while(keys.hasNext()){val k=keys.next();val i=saved.optJSONObject(k)?:continue;when(i.optString("t")){"s"->editor.putString(k,i.optString("v"));"b"->editor.putBoolean(k,i.optBoolean("v"));"i"->editor.putInt(k,i.optInt("v"));"l"->editor.putLong(k,i.optLong("v"));"f"->editor.putFloat(k,i.optDouble("v").toFloat());"set"->{val a=i.optJSONArray("v")?:JSONArray();val set=buildSet{for(x in 0 until a.length())a.optString(x).takeIf{it.isNotBlank()}?.let(::add)};editor.putStringSet(k,set)}}};editor.apply()}
    private fun restoreRuntime(context:Context,saved:JSONObject):Int { val prefs=context.applicationContext.getSharedPreferences("horatrack_v2_test_runtime",Context.MODE_PRIVATE);val currentOpen=numeric(prefs.all["real_entry"])>0L&&numeric(prefs.all["real_exit"])==0L;val savedHistory=decodeTypedString(saved.optJSONObject("history"))?.let{runCatching{JSONArray(it)}.getOrNull()}?:JSONArray();val current=runCatching{JSONArray(prefs.getString("history","[]")?:"[]")}.getOrElse{JSONArray()};val seen=mutableSetOf<String>();for(i in 0 until current.length())current.optJSONObject(i)?.let{seen+=historySignature(it)};var merged=0;for(i in 0 until savedHistory.length()){val item=savedHistory.optJSONObject(i)?:continue;val sig=historySignature(item);if(sig !in seen){current.put(item);seen+=sig;merged++}};prefs.edit().putString("history",current.toString()).apply();if(!currentOpen&&numeric(prefs.all["real_entry"])==0L){val rest=JSONObject(saved.toString()).apply{remove("history")};mergePreferences(context,"horatrack_v2_test_runtime",rest);prefs.edit().putString("history",current.toString()).apply()};return merged }
    private fun decodeTypedString(i:JSONObject?):String?=i?.takeIf{it.optString("t")=="s"}?.optString("v")
    private fun historySignature(o:JSONObject)=listOf(o.optString("id"),o.optLong("realEntry",0L),o.optLong("realExit",0L),o.optLong("countedEntry",0L),o.optLong("countedExit",0L)).joinToString(":")
    private fun numeric(v:Any?):Long=when(v){is Number->v.toLong();is String->v.toLongOrNull()?:0L;else->0L}
    private fun treeRootDocumentUri(u:Uri):Uri=DocumentsContract.buildDocumentUriUsingTree(u,DocumentsContract.getTreeDocumentId(u))
    private fun ensureDirectory(c:Context,p:Uri,n:String):Uri=findChild(c,p,n,DocumentsContract.Document.MIME_TYPE_DIR)?:DocumentsContract.createDocument(c.contentResolver,p,DocumentsContract.Document.MIME_TYPE_DIR,n)?:error("Impossible de créer $n")
    private fun ensureFile(c:Context,p:Uri,n:String,m:String):Uri=findChild(c,p,n,m)?:DocumentsContract.createDocument(c.contentResolver,p,m,n)?:error("Impossible de créer $n")
    private fun findChild(c:Context,p:Uri,n:String,m:String):Uri?{val id=DocumentsContract.getDocumentId(p);val children=DocumentsContract.buildChildDocumentsUriUsingTree(p,id);val projection=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE);c.contentResolver.query(children,projection,null,null,null)?.use{cur->val ci=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val cn=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);val cm=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);while(cur.moveToNext())if(cur.getString(cn)==n&&cur.getString(cm)==m)return DocumentsContract.buildDocumentUriUsingTree(p,cur.getString(ci))};return null}
}
