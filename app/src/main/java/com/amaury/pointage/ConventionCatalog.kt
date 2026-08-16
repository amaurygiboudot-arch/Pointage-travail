package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors

object ConventionCatalog {
    data class OvertimeTier(val fromHour: Double, val toHour: Double?, val multiplier: Double)
    data class Convention(
        val idcc: String,
        val shortName: String,
        val fullName: String,
        val rulesIntegrated: Boolean = false,
        val overtimeTiers: List<OvertimeTier> = legalOvertimeTiers(),
        val nightMultiplier: Double? = null,
        val sundayHolidayMultiplier: Double? = null,
        val advantages: List<String> = emptyList(),
        val cautions: List<String> = emptyList()
    ) {
        val displayName: String get() = if (idcc.isBlank()) shortName else "$shortName — IDCC $idcc"
        fun matches(query: String): Boolean {
            val q=query.trim().lowercase(); if(q.isBlank()) return true
            return idcc.lowercase().contains(q)||shortName.lowercase().contains(q)||fullName.lowercase().contains(q)
        }
    }

    private fun legalOvertimeTiers()=listOf(OvertimeTier(35.0,43.0,1.25),OvertimeTier(43.0,null,1.50))

    private val builtIns=listOf(
        Convention("0292","Plasturgie","Transformation des matières plastiques",true,legalOvertimeTiers(),1.12,2.00,
            listOf("Heures supplémentaires majorées à 25 % puis 50 %.","Majoration conventionnelle de nuit de 12 % lorsque les conditions sont réunies.","Travail exceptionnel le dimanche ou un jour férié : majoration conventionnelle de 100 %.","Repos compensateur prévu pour les travailleurs de nuit."),
            listOf("Un accord d'entreprise peut prévoir des règles différentes ou plus favorables.","Certaines majorations ne se cumulent pas entre elles.")),
        Convention("1979","Hôtels, cafés, restaurants (HCR)","Convention collective nationale des hôtels, cafés restaurants",true,
            listOf(OvertimeTier(35.0,39.0,1.10),OvertimeTier(39.0,42.0,1.20),OvertimeTier(42.0,43.0,1.25),OvertimeTier(43.0,null,1.50)),
            advantages=listOf("Barème conventionnel spécifique des heures supplémentaires dans les dispositifs HCR concernés."),
            cautions=listOf("La modulation ou l'annualisation peut modifier le déclenchement des heures supplémentaires.")),
        Convention("3248","Métallurgie","Convention collective nationale de la métallurgie",cautions=listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("0016","Transports routiers","Transports routiers et activités auxiliaires du transport",cautions=listOf("Règles spécifiques selon l'emploi : régime légal provisoire.")),
        Convention("1486","Syntec","Bureaux d'études techniques, cabinets d'ingénieurs-conseils et sociétés de conseils",cautions=listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("2216","Commerce alimentaire","Commerce de détail et de gros à prédominance alimentaire",cautions=listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("1596","Bâtiment — jusqu'à 10 salariés","Ouvriers des entreprises du bâtiment occupant jusqu'à 10 salariés",cautions=listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("1597","Bâtiment — plus de 10 salariés","Ouvriers des entreprises du bâtiment occupant plus de 10 salariés",cautions=listOf("Règles détaillées non encore intégrées : régime légal provisoire.")),
        Convention("","Régime légal / autre convention","Régime légal sans règle conventionnelle intégrée",true,legalOvertimeTiers(),advantages=listOf("Calcul basé sur les majorations légales de référence."),cautions=listOf("Ne tient pas compte d'une convention collective ou d'un accord d'entreprise plus favorable."))
    )

    private const val PREFS="convention_catalog"
    private const val CACHE="kali_cache"
    private const val SOURCE="https://raw.githubusercontent.com/SocialGouv/kali-data/master/REFERENCES.md"

    @Volatile private var current: List<Convention> = builtIns
    val conventions: List<Convention> get() = current

    init {
        // Premier chargement réseau en arrière-plan. Le catalogue local minimal reste utilisable immédiatement.
        Executors.newSingleThreadExecutor().execute {
            val parsed=downloadCatalog()
            if(parsed.isNotEmpty()) current=mergeWithBuiltIns(parsed)
        }
    }

    fun initialize(context: Context) {
        current = mergeWithBuiltIns(decodeCache(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CACHE,null)))
        refreshAsync(context)
    }

    private fun mergeWithBuiltIns(dynamic: List<Convention>): List<Convention> {
        val byId=linkedMapOf<String,Convention>()
        dynamic.forEach { if(it.idcc.isNotBlank()) byId[it.idcc.padStart(4,'0')]=it }
        builtIns.forEach { if(it.idcc.isNotBlank()) byId[it.idcc.padStart(4,'0')]=it }
        return byId.values.sortedBy { it.shortName.lowercase() } + builtIns.first { it.idcc.isBlank() }
    }

    fun all(context: Context): List<Convention> {
        val dynamic=decodeCache(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CACHE,null))
        if(dynamic.isNotEmpty()) current=mergeWithBuiltIns(dynamic)
        return current
    }

    fun findByIdcc(context:Context,idcc:String?):Convention? {
        if(idcc==null)return null
        val normalized=idcc.padStart(4,'0')
        return all(context).firstOrNull{it.idcc.padStart(4,'0')==normalized}
    }

    fun findByIdcc(idcc:String?):Convention? {
        if(idcc==null)return null
        val normalized=idcc.padStart(4,'0')
        return current.firstOrNull{it.idcc.padStart(4,'0')==normalized}
    }

    fun refreshAsync(context:Context,onDone:(Int)->Unit={}) {
        Executors.newSingleThreadExecutor().execute {
            val parsed=downloadCatalog()
            if(parsed.isNotEmpty()) {
                context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(CACHE,encodeCache(parsed)).apply()
                current=mergeWithBuiltIns(parsed)
            }
            Handler(Looper.getMainLooper()).post{onDone(if(parsed.isEmpty()) current.count{it.idcc.isNotBlank()} else parsed.size)}
        }
    }

    private fun downloadCatalog():List<Convention> = runCatching {
        URL(SOURCE).readText().lineSequence().mapNotNull { line ->
            val p=line.split('|').map{it.trim()}
            if(p.size<4 || !p[0].startsWith("KALICONT")) return@mapNotNull null
            val raw=p[1].filter{it.isDigit()}; if(raw.isBlank()) return@mapNotNull null
            val idcc=raw.padStart(4,'0'); val title=p[2].trim(); if(title.isBlank()) return@mapNotNull null
            Convention(idcc,title,title,false,cautions=listOf("Convention issue du catalogue KALI. Règles salariales spécifiques non encore intégrées : régime légal provisoire."))
        }.distinctBy{it.idcc}.toList()
    }.getOrElse{emptyList()}

    private fun encodeCache(items:List<Convention>):String { val a=JSONArray();items.forEach{a.put(JSONObject().put("idcc",it.idcc).put("title",it.fullName))};return a.toString() }
    private fun decodeCache(raw:String?):List<Convention>{if(raw.isNullOrBlank())return emptyList();return runCatching{val a=JSONArray(raw);buildList{for(i in 0 until a.length()){val o=a.getJSONObject(i);val id=o.optString("idcc");val t=o.optString("title");if(id.isNotBlank()&&t.isNotBlank())add(Convention(id,t,t,false,cautions=listOf("Convention issue du catalogue KALI. Règles salariales spécifiques non encore intégrées : régime légal provisoire.")))}}}.getOrElse{emptyList()}}
}
