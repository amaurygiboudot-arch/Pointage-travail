package com.amaury.pointage

/** Local wall-clock interval, start included/end excluded. Manual context always wins. */
object NightContextPolicyV2 {
    fun resolve(manual: String, enabled: Boolean, start: Int, end: Int, minute: Int): String {
        require(start in 0..1439 && end in 0..1439 && minute in 0..1439 && start != end)
        if (!enabled || manual != "normal") return manual
        val inside = if (start < end) minute >= start && minute < end else minute >= start || minute < end
        return if (inside) "night" else manual
    }
}
