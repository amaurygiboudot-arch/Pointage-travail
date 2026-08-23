package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DiamondDesignerProjectStore {
    private const val PREFS = "diamond_designer_projects"
    private const val KEY = "projects"

    data class Project(val name: String, val elements: List<DiamondDesignerCanvas.DesignElement>)

    fun list(context: Context): MutableList<Project> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> projectFromJson(arr.getJSONObject(i)) }
        }.getOrElse { mutableListOf() }
    }

    fun save(context: Context, project: Project) {
        val projects = list(context)
        val index = projects.indexOfFirst { it.name.equals(project.name, true) }
        if (index >= 0) projects[index] = project else projects.add(project)
        persist(context, projects)
    }

    fun delete(context: Context, name: String) {
        val projects = list(context)
        projects.removeAll { it.name == name }
        persist(context, projects)
    }

    fun projectToJson(project: Project): String = projectJson(project).toString(2)

    fun projectFromJson(text: String): Project = projectFromJson(JSONObject(text))

    private fun persist(context: Context, projects: List<Project>) {
        val arr = JSONArray(); projects.forEach { arr.put(projectJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun projectJson(project: Project) = JSONObject().apply {
        put("format", "diamond_designer_project_v1")
        put("name", project.name)
        put("elements", JSONArray().apply { project.elements.forEach { put(elementJson(it)) } })
    }

    private fun projectFromJson(o: JSONObject): Project {
        val arr = o.optJSONArray("elements") ?: JSONArray()
        return Project(o.optString("name", "Projet"), List(arr.length()) { i -> elementFromJson(arr.getJSONObject(i)) })
    }

    private fun elementJson(e: DiamondDesignerCanvas.DesignElement) = JSONObject().apply {
        put("type", e.type.name); put("name", e.name)
        put("x",e.x); put("y",e.y); put("width",e.width); put("height",e.height); put("rotation",e.rotation)
        put("alpha",e.alpha); put("transparency",e.transparency); put("translucency",e.translucency)
        put("lens",e.lensStrength); put("light",e.lightAngle)
        put("ring1",e.ring1Gain); put("ring2",e.ring2Gain); put("ring3",e.ring3Gain)
        put("ringCount",e.ringCount); put("facetDensity",e.facetDensity); put("facetDepth",e.facetDepth)
        put("ring1Visible",e.ring1Visible); put("ring2Visible",e.ring2Visible); put("ring3Visible",e.ring3Visible)
        put("coreVisible",e.coreVisible); put("facetsVisible",e.facetsVisible); put("edgesVisible",e.edgesVisible)
        put("edgeWidth",e.edgeWidth); put("edgeAlpha",e.edgeAlpha); put("edgeContrast",e.edgeContrast); put("edgeSoftness",e.edgeSoftness)
        put("radialEdges",e.radialEdgeGain); put("circularEdges",e.circularEdgeGain)
        put("frameWidth",e.frameWidth); put("cornerRadius",e.cornerRadius)
        put("locked",e.locked); put("visible",e.visible)
    }

    private fun elementFromJson(o: JSONObject) = DiamondDesignerCanvas.DesignElement(
        type = runCatching { DiamondDesignerCanvas.ElementType.valueOf(o.optString("type")) }.getOrDefault(DiamondDesignerCanvas.ElementType.ENTRY_BUTTON),
        x=o.optDouble("x",20.0).toFloat(), y=o.optDouble("y",80.0).toFloat(), width=o.optDouble("width",300.0).toFloat(), height=o.optDouble("height",300.0).toFloat(),
        rotation=o.optDouble("rotation",0.0).toFloat(), alpha=o.optDouble("alpha",1.0).toFloat(), transparency=o.optDouble("transparency",0.0).toFloat(), translucency=o.optDouble("translucency",0.0).toFloat(),
        lensStrength=o.optDouble("lens",.5).toFloat(), lightAngle=o.optDouble("light",305.0).toFloat(), ring1Gain=o.optDouble("ring1",1.0).toFloat(), ring2Gain=o.optDouble("ring2",1.0).toFloat(), ring3Gain=o.optDouble("ring3",1.0).toFloat(),
        ringCount=o.optInt("ringCount",3).coerceIn(1,3), facetDensity=o.optInt("facetDensity",32).coerceIn(8,64), facetDepth=o.optDouble("facetDepth",.68).toFloat().coerceIn(0f,1f),
        ring1Visible=o.optBoolean("ring1Visible",true), ring2Visible=o.optBoolean("ring2Visible",true), ring3Visible=o.optBoolean("ring3Visible",true), coreVisible=o.optBoolean("coreVisible",true), facetsVisible=o.optBoolean("facetsVisible",true), edgesVisible=o.optBoolean("edgesVisible",true),
        edgeWidth=o.optDouble("edgeWidth",1.4).toFloat(), edgeAlpha=o.optDouble("edgeAlpha",.55).toFloat(), edgeContrast=o.optDouble("edgeContrast",.62).toFloat(), edgeSoftness=o.optDouble("edgeSoftness",.08).toFloat(), radialEdgeGain=o.optDouble("radialEdges",1.0).toFloat(), circularEdgeGain=o.optDouble("circularEdges",1.0).toFloat(),
        frameWidth=o.optDouble("frameWidth",12.0).toFloat(), cornerRadius=o.optDouble("cornerRadius",24.0).toFloat(), locked=o.optBoolean("locked",false), visible=o.optBoolean("visible",true), name=o.optString("name","Élément")
    )
}
