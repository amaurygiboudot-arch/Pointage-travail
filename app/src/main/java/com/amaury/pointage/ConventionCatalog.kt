package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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
        Convention("3127","Services à la personne","Convention collective nationale des entreprises de services à la personne du 20 septembre 2012",cautions=listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("2941","Aide et services à domicile","Convention collective nationale de la branche de l'aide, de l'accompagnement, des soins et des services à domicile du 21 mai 2010",cautions=listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("3239","Particuliers employeurs / emploi à domicile","Convention collective de la branche du secteur des particuliers employeurs et de l'emploi à domicile du 15 mars 2021",cautions=listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")),
        Convention("","Régime légal / autre convention","Régime légal sans règle conventionnelle intégrée",true,legalOvertimeTiers(),advantages=listOf("Calcul basé sur les majorations légales de référence."),cautions=listOf("Ne tient pas compte d'une convention collective ou d'un accord d'entreprise plus favorable."))
    )

    private const val PREFS="convention_catalog"
    private const val CACHE="kali_cache"

    // Source officielle de l'Etat : Légifrance / DILA.
    // On récupère uniquement les textes de base, 100 par page.
    private const val SOURCE_BASE="https://www.legifrance.gouv.fr/liste/idcc?facetteTexteBase=TEXTE_BASE&pageSize=100&sortValue=DATE_UPDATE&page="

    @Volatile private var current: List<Convention> = builtIns
    val conventions: List<Convention> get() = current

    init {
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
        val byId=linkedMapOf<String,Convention>()
        var page=1
        var pagesWithoutNewItems=0

        while(page<=20 && pagesWithoutNewItems<2) {
            val html=downloadHtml(SOURCE_BASE + page)
            if(html.isBlank()) break
            val before=byId.size
            parseLegifrancePage(html).forEach { byId[it.idcc]=it }
            pagesWithoutNewItems = if(byId.size==before) pagesWithoutNewItems+1 else 0
            page++
        }
        byId.values.toList()
    }.getOrElse{emptyList()}

    private fun downloadHtml(url:String):String {
        val connection=(URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=12000
            readTimeout=15000
            requestMethod="GET"
            setRequestProperty("User-Agent","PointageTravail/1.0 (Android; catalogue conventions Légifrance)")
            setRequestProperty("Accept","text/html,application/xhtml+xml")
        }
        return try {
            if(connection.responseCode !in 200..299) return ""
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun parseLegifrancePage(html:String):List<Convention> {
        val result=linkedMapOf<String,Convention>()
        // Les résultats Légifrance sont rendus côté serveur avec le titre dans un h2,
        // puis l'IDCC dans le même bloc résultat.
        val blockRegex=Regex("(?is)<h2[^>]*>(.*?)</h2>(.*?)(?=<h2|$)")
        val idccRegex=Regex("(?i)IDCC(?:\\s|&nbsp;|&#160;)*(\\d{1,4})")
        blockRegex.findAll(html).forEach { match ->
            val title=cleanHtml(match.groupValues[1])
            val body=match.groupValues[2]
            val id=idccRegex.find(body)?.groupValues?.getOrNull(1)?.padStart(4,'0') ?: return@forEach
            if(title.isBlank()) return@forEach
            result[id]=Convention(
                id,
                shortTitle(title),
                title,
                false,
                cautions=listOf("Convention issue directement du catalogue officiel Légifrance (DILA). Règles salariales spécifiques non encore intégrées : régime légal provisoire.")
            )
        }
        return result.values.toList()
    }

    private fun shortTitle(title:String):String {
        var t=title
            .replace(Regex("(?i)^Convention collective nationale\\s+(de|des|du|pour|relative à|concernant)?\\s*"),"")
            .replace(Regex("(?i)^Convention collective\\s+(de|des|du|pour)?\\s*"),"")
            .trim()
        if(t.length>110) t=t.take(107).trimEnd()+"…"
        return t.ifBlank{title}
    }

    private fun cleanHtml(value:String):String = value
        .replace(Regex("(?is)<[^>]+>")," ")
        .replace("&nbsp;"," ")
        .replace("&#160;"," ")
        .replace("&amp;","&")
        .replace("&quot;","\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("\\s+")," ")
        .trim()

    private fun encodeCache(items:List<Convention>):String { val a=JSONArray();items.forEach{a.put(JSONObject().put("idcc",it.idcc).put("title",it.fullName).put("short",it.shortName))};return a.toString() }
    private fun decodeCache(raw:String?):List<Convention>{if(raw.isNullOrBlank())return emptyList();return runCatching{val a=JSONArray(raw);buildList{for(i in 0 until a.length()){val o=a.getJSONObject(i);val id=o.optString("idcc");val t=o.optString("title");val s=o.optString("short").ifBlank{shortTitle(t)};if(id.isNotBlank()&&t.isNotBlank())add(Convention(id,s,t,false,cautions=listOf("Convention issue directement du catalogue officiel Légifrance (DILA). Règles salariales spécifiques non encore intégrées : régime légal provisoire.")))}}}.getOrElse{emptyList()}}
}
