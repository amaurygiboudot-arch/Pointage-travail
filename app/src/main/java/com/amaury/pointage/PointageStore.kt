package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

object PointageStore {
    private const val PREFS = "pointage"
    private const val KEY = "data"
    private const val ICON_SYNC_DELAY_MS = 1500L
    private const val ENTRY_SLOT_MS = 30L * 60L * 1000L
    private val storageLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIconSync: Runnable? = null

    private fun loadUnlocked(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        if (raw.isBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("corrupt_data_backup", raw).apply()
            JSONArray()
        }
    }
    private fun saveUnlocked(context: Context, data: JSONArray) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, data.toString()).apply() }
    fun load(context: Context): JSONArray = synchronized(storageLock) { loadUnlocked(context) }
    fun save(context: Context, data: JSONArray) = synchronized(storageLock) {
        val last = if (data.length() > 0) data.optJSONObject(data.length() - 1) else null
        if (last?.optBoolean("manual", false) == true && !last.has("autoPauseMinutes")) {
            val slot=last.optInt("companySlot",0); last.put("autoPauseMinutes",if(slot in 1..2) CompanyBasePauseSettings.baseMinutes(context,slot) else 0); if(!last.has("pauses")) last.put("pauses",JSONArray())
        }; saveUnlocked(context,data)
    }
    internal fun <T> update(context: Context, block:(JSONArray)->T):T=synchronized(storageLock){val d=loadUnlocked(context);val r=block(d);saveUnlocked(context,d);r}
    fun hasOpen(context: Context)=findOpenSession(load(context))!=null
    fun isPaused(context: Context):Boolean{val o=findOpenSession(load(context))?:return false;return currentPause(o)!=null}
    fun isPausedAutomatically(context: Context):Boolean{val o=findOpenSession(load(context))?:return false;return currentPause(o)?.optBoolean("automatic",false)==true}
    private fun scheduleIconSync(context:Context){pendingIconSync?.let(mainHandler::removeCallbacks);val app=context.applicationContext;val task=Runnable{IconSwitcher.sync(app)};pendingIconSync=task;mainHandler.postDelayed(task,ICON_SYNC_DELAY_MS)}

    /** L'heure d'arrivée réelle est conservée séparément. L'embauche comptée est arrondie à la prochaine tranche de 30 min. */
    private fun hiringTimeFromArrival(arrival:Long):Long {
        val remainder = Math.floorMod(arrival, ENTRY_SLOT_MS)
        return if (remainder == 0L) arrival else arrival + (ENTRY_SLOT_MS - remainder)
    }

    fun entry(context:Context,zoneId:String?=null,zoneAddress:String?=null):Boolean{
        val now=System.currentTimeMillis()
        val resumed=synchronized(storageLock){val d=loadUnlocked(context);val item=findOpenSession(d)?:return@synchronized false;val p=openPause(item)?:return@synchronized false;val start=p.optLong("start",-1L);if(start<=0L||now<start)return@synchronized false;p.put("end",now).put("resumedByEntry",true);saveUnlocked(context,d);true}
        if(resumed){updateWidgets(context);scheduleIconSync(context);DriveBackupManager.syncCurrentMonthAsync(context);return true}

        val detected=if(zoneId.isNullOrBlank()&&zoneAddress.isNullOrBlank())currentActiveZone(context) else null
        val finalId=zoneId?:detected?.first
        val raw=zoneAddress?:detected?.second
        val finalAddress=raw?.trim()?.takeIf{it.isNotBlank()}?.let{PlaceNames.display(context,it)}
        val shift=ShiftProfileManager.resolve(context,now)
        val slot=resolveCompanySlot(context,raw)
        val companyPause=CompanyBasePauseSettings.baseMinutes(context,slot)
        val fallback=ShiftProfileManager.pauseMinutes(context,shift)
        val base=if(companyPause>0)companyPause else fallback
        val countedEntry=hiringTimeFromArrival(now)

        val changed=synchronized(storageLock){val d=loadUnlocked(context);if(findOpenSession(d)!=null)false else{
            val item=JSONObject().put("entry",countedEntry).put("arrivalTime",now).put("exit",JSONObject.NULL).put("pauses",JSONArray()).put("shiftType",shift.id).put("companySlot",slot).put("autoPauseMinutes",base)
            if(!finalId.isNullOrBlank())item.put("zoneId",finalId)
            if(!finalAddress.isNullOrBlank())item.put("zoneAddress",finalAddress)
            d.put(item);saveUnlocked(context,d);true}}
        if(!changed)return false
        PauseScheduleManager.applyCurrentWindow(context);updateWidgets(context);scheduleIconSync(context);return true
    }

    fun startPause(context:Context,automatic:Boolean=false):Boolean{val now=System.currentTimeMillis();val changed=synchronized(storageLock){val d=loadUnlocked(context);val item=findOpenSession(d)?:return@synchronized false;if(openPause(item)!=null)return@synchronized false;val entry=item.optLong("entry",-1L);if(entry<=0L||now<entry)return@synchronized false;val ps=item.optJSONArray("pauses")?:JSONArray().also{item.put("pauses",it)};val p=JSONObject().put("start",now).put("end",JSONObject.NULL);if(automatic)p.put("automatic",true);ps.put(p);saveUnlocked(context,d);true};if(!changed)return false;updateWidgets(context);scheduleIconSync(context);return true}
    fun resumePause(context:Context,automaticOnly:Boolean=false):Boolean{val now=System.currentTimeMillis();val changed=synchronized(storageLock){val d=loadUnlocked(context);val item=findOpenSession(d)?:return@synchronized false;val p=if(automaticOnly)openPause(item)?.takeIf{it.optBoolean("automatic",false)} else currentPause(item);p?:return@synchronized false;val start=p.optLong("start",-1L);if(start<=0L||now<start)return@synchronized false;p.put("end",now);saveUnlocked(context,d);true};if(!changed)return false;updateWidgets(context);scheduleIconSync(context);DriveBackupManager.syncCurrentMonthAsync(context);return true}
    fun resumeAnyPause(context:Context):Boolean=resumePause(context,automaticOnly=false)
    fun addManualPause(context:Context,pauseStart:Long,pauseEnd:Long):Boolean{if(pauseStart<=0L||pauseEnd<=pauseStart)return false;val changed=synchronized(storageLock){val d=loadUnlocked(context);var target:JSONObject?=null;for(i in d.length()-1 downTo 0){val item=d.optJSONObject(i)?:continue;val e=item.optLong("entry",-1L);if(e<=0L)continue;val end=if(item.isNull("exit"))System.currentTimeMillis() else item.optLong("exit",-1L);if(end>=e&&pauseStart>=e&&pauseEnd<=end){target=item;break}};val item=target?:return@synchronized false;val ps=item.optJSONArray("pauses")?:JSONArray().also{item.put("pauses",it)};ps.put(JSONObject().put("start",pauseStart).put("end",pauseEnd).put("manual",true));saveUnlocked(context,d);true};if(!changed)return false;updateWidgets(context);DriveBackupManager.syncCurrentMonthAsync(context);return true}
    fun pauseDuration(item:JSONObject,until:Long=System.currentTimeMillis()):Long{val entry=item.optLong("entry",-1L);if(entry<=0L)return 0L;val sessionEnd=if(item.isNull("exit"))until else item.optLong("exit",until);if(sessionEnd<=entry)return 0L;val raw=sessionEnd-entry;val base=item.optInt("autoPauseMinutes",0).coerceIn(0,240)*60000L;val ps=item.optJSONArray("pauses");val intervals=mutableListOf<Pair<Long,Long>>();if(ps!=null)for(i in 0 until ps.length()){val p=ps.optJSONObject(i)?:continue;if(base>0&&p.optBoolean("automatic",false))continue;val s=p.optLong("start",-1L);val e=if(p.isNull("end"))until else p.optLong("end",-1L);if(s<=0L||e<=s)continue;val a=s.coerceAtLeast(entry);val b=e.coerceAtMost(sessionEnd);if(b>a)intervals+=a to b};var additional=0L;if(intervals.isNotEmpty()){intervals.sortBy{it.first};var s=intervals.first().first;var e=intervals.first().second;for(i in 1 until intervals.size){val(a,b)=intervals[i];if(a<=e)e=maxOf(e,b) else{additional+=e-s;s=a;e=b}};additional+=e-s};return(base+additional).coerceIn(0L,raw)}
    fun workedDuration(item:JSONObject,until:Long=System.currentTimeMillis()):Long{val e=item.optLong("entry",-1L);if(e<=0L)return 0L;val end=if(item.isNull("exit"))until else item.optLong("exit",until);if(end<=e)return 0L;return((end-e)-pauseDuration(item,end)).coerceAtLeast(0L)}
    fun exit(context:Context):Boolean{val now=System.currentTimeMillis();val changed=synchronized(storageLock){val d=loadUnlocked(context);for(i in d.length()-1 downTo 0){val item=d.optJSONObject(i)?:continue;if(!item.isNull("exit"))continue;val entry=item.optLong("entry",-1L);if(entry<=0L||now<entry)continue;openPause(item)?.let{p->if(p.optLong("start",-1L)>0L)p.put("end",now)};item.put("exit",now);saveUnlocked(context,d);return@synchronized true};false};if(!changed)return false;updateWidgets(context);scheduleIconSync(context);DriveBackupManager.syncCurrentMonthAsync(context);return true}
    fun manualPausesForDay(context:Context,dayStart:Long,dayEnd:Long):List<Pair<Long,Long>>{if(dayStart<=0L||dayEnd<=dayStart)return emptyList();val result=mutableListOf<Pair<Long,Long>>();val d=load(context);for(i in 0 until d.length()){val ps=d.optJSONObject(i)?.optJSONArray("pauses")?:continue;for(j in 0 until ps.length()){val p=ps.optJSONObject(j)?:continue;if(!p.optBoolean("manual",false))continue;val s=p.optLong("start",-1L);val e=p.optLong("end",-1L);if(s>=dayStart&&s<dayEnd&&e>s)result+=s to e}};return result.distinct().sortedBy{it.first}.take(5)}
    private fun currentPause(item:JSONObject,now:Long=System.currentTimeMillis()):JSONObject?{val ps=item.optJSONArray("pauses")?:return null;for(i in ps.length()-1 downTo 0){val p=ps.optJSONObject(i)?:continue;val s=p.optLong("start",-1L);if(s<=0L||now<s)continue;if(p.isNull("end"))return p;val e=p.optLong("end",-1L);if(e>s&&now<e)return p};return null}
    private fun openPause(item:JSONObject):JSONObject?{val ps=item.optJSONArray("pauses")?:return null;for(i in ps.length()-1 downTo 0){val p=ps.optJSONObject(i)?:continue;if(p.optLong("start",-1L)>0L&&p.isNull("end"))return p};return null}
    private fun findOpenSession(d:JSONArray):JSONObject?{for(i in d.length()-1 downTo 0){val item=d.optJSONObject(i)?:continue;if(item.optLong("entry",-1L)>0L&&item.isNull("exit"))return item};return null}
    private fun resolveCompanySlot(context:Context,rawAddress:String?):Int{val salary=context.getSharedPreferences("salary_settings",Context.MODE_PRIVATE);val gps=context.getSharedPreferences("gps_settings",Context.MODE_PRIVATE);val address=rawAddress?.trim().orEmpty();if(address.isNotBlank()){val map=runCatching{JSONObject(gps.getString("address_company_slots","{}")?:"{}")}.getOrNull();val direct=map?.optInt(address,0)?:0;if(direct in 1..2)return direct};return if(salary.getString("company_name","").orEmpty().isNotBlank())1 else 1}
    private fun currentActiveZone(context:Context):Pair<String,String>?{val p=context.getSharedPreferences("gps_settings",Context.MODE_PRIVATE);if(!p.getBoolean("enabled",false))return null;val ids=p.getStringSet("active_zones",emptySet()).orEmpty();if(ids.isEmpty())return null;return runCatching{val z=JSONArray(p.getString("zones","[]")?:"[]");for(i in 0 until z.length()){val o=z.optJSONObject(i)?:continue;val id=o.optString("id");if(id in ids){val a=o.optString("address").trim();if(a.isNotBlank())return@runCatching id to a}};null}.getOrNull()}
    private fun updateWidgets(context:Context){PointageWidgetProvider.updateAll(context);QuickActionsWidgetProvider.updateAll(context)}
}
