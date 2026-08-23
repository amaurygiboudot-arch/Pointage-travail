package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object DiamondDesignerAssistant {
    data class Result(val reply: String, val actions: JSONArray)

    fun ask(message: String, state: String): Result {
        val endpoint = BuildConfig.DESIGNER_AI_ENDPOINT.trim()
        require(endpoint.startsWith("https://")) { "Assistant non configuré : endpoint HTTPS manquant" }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        val payload = JSONObject()
            .put("protocol", "diamond_designer_v1")
            .put("message", message)
            .put("designer_state", state)

        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        connection.disconnect()
        if (status !in 200..299) error("Assistant HTTP $status : ${body.take(220)}")

        val json = JSONObject(body)
        return Result(
            reply = json.optString("reply", "Modification appliquée."),
            actions = json.optJSONArray("actions") ?: JSONArray()
        )
    }

    fun apply(canvas: DiamondDesignerCanvas, actions: JSONArray): Int {
        var applied = 0
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val type = a.optString("type")
            val ok = when (type) {
                "set_lens" -> { canvas.setSelectedLens(a.doubleValue("value", 0.5)); true }
                "set_ring" -> { canvas.setSelectedRingGain(a.optInt("ring", 1).coerceIn(1, 3), a.doubleValue("value", 1.0)); true }
                "set_alpha" -> { canvas.setSelectedAlpha(a.doubleValue("value", 1.0)); true }
                "set_rotation" -> { canvas.setSelectedRotation(a.doubleValue("value", 0.0)); true }
                "set_light_angle" -> { canvas.setSelectedLightAngle(a.doubleValue("value", 305.0)); true }
                "set_edge_width" -> { canvas.setSelectedEdgeWidth(a.doubleValue("value", 1.4)); true }
                "set_edge_alpha" -> { canvas.setSelectedEdgeAlpha(a.doubleValue("value", 0.55)); true }
                "set_edge_contrast" -> { canvas.setSelectedEdgeContrast(a.doubleValue("value", 0.62)); true }
                "set_edge_softness" -> { canvas.setSelectedEdgeSoftness(a.doubleValue("value", 0.08)); true }
                "set_radial_edges" -> { canvas.setSelectedRadialEdgeGain(a.doubleValue("value", 1.0)); true }
                "set_circular_edges" -> { canvas.setSelectedCircularEdgeGain(a.doubleValue("value", 1.0)); true }
                "add_entry" -> { canvas.addEntryButton(); true }
                "add_pause" -> { canvas.addPauseButton(); true }
                "add_exit" -> { canvas.addExitButton(); true }
                "add_frame" -> { canvas.addFrame(); true }
                "add_background" -> { canvas.addBackground(); true }
                "duplicate" -> { canvas.duplicateSelected(); true }
                "delete" -> { canvas.deleteSelected(); true }
                "bring_forward" -> { canvas.bringForward(); true }
                "send_backward" -> { canvas.sendBackward(); true }
                "toggle_lock" -> { canvas.toggleLock(); true }
                else -> false
            }
            if (ok) applied++
        }
        return applied
    }

    private fun JSONObject.doubleValue(key: String, default: Double): Float =
        optDouble(key, default).toFloat()
}
