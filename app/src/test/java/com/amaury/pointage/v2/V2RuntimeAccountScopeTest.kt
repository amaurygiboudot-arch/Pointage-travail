package com.amaury.pointage.v2

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class V2RuntimeAccountScopeTest {
    @Test fun unknownAuthenticationIsNotGuest() {
        assertNull(V2RuntimeAccountScope.authenticationScope(false, null))
        assertNull(V2RuntimeAccountScope.authenticationScope(true, "  "))
        assertEquals("guest", V2RuntimeAccountScope.authenticationScope(true, null))
        assertEquals("uid:guest", V2RuntimeAccountScope.authenticationScope(true, "guest"))
    }

    @Test fun runtimeAndMigrationFilesAreStableAndDisjointPerAccount() {
        val runtime = "horatrack_v2_test_runtime"
        val guest = V2RuntimeAccountScope.fileName(runtime, "guest")
        assertEquals(guest, V2RuntimeAccountScope.fileName(runtime, "guest"))
        assertNotEquals(guest, V2RuntimeAccountScope.fileName(runtime, "uid:guest"))
        assertNotEquals(V2RuntimeAccountScope.fileName(runtime, "uid:a/b"), V2RuntimeAccountScope.fileName(runtime, "uid:a_b"))
        assertNotEquals(guest, runtime)
        assertNotEquals(guest, V2RuntimeAccountScope.fileName("horatrack_v2_migration", "guest"))
        assertNull(V2RuntimeAccountScope.fileName(runtime, null))
    }

    @Test fun chainedEditorCannotCommitAfterAccountChangesAndOldReadsAreHidden() {
        var owner = "A"
        var committed = false
        val values = mutableMapOf<String, Any>("history" to "A-history")
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, _ ->
            when (method.name) {
                "commit" -> { committed = true; true }
                "apply" -> null
                else -> editor
            }
        } as SharedPreferences.Editor
        val delegate = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values
                "getString" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                else -> null
            }
        } as SharedPreferences
        val prefs = V2RuntimeAccountScope.guardedPreferences(delegate) { owner == "A" }
        assertEquals("A-history", prefs.getString("history", null))
        val captured = prefs.edit().putString("history", "candidate").remove("old")
        owner = "B"
        assertFalse(captured.commit())
        captured.apply()
        assertFalse(committed)
        assertTrue(prefs.all.isEmpty())
        assertNull(prefs.getString("history", null))
        owner = "A"
        assertTrue(prefs.edit().putString("history", "candidate").commit())
        assertTrue(committed)
    }
}
