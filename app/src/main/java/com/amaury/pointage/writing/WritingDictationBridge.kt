package com.amaury.pointage.writing

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amaury.pointage.WritingDictationActivity
import java.util.UUID

/** In-memory one-use result channel; neither the field text nor audio enters an Intent or clipboard. */
internal object WritingDictationBridge {
    data class Request(val language: String, val isValid: () -> Boolean, val deliver: (String) -> Unit,
                       val expiresAt: Long, var preview: String? = null)
    private val requests = mutableMapOf<String, Request>()
    private val main = Handler(Looper.getMainLooper())
    fun start(context: Context, language: String, isValid: () -> Boolean, deliver: (String) -> Unit): String {
        val token = UUID.randomUUID().toString()
        requests[token] = Request(language, isValid, deliver, SystemClock.elapsedRealtime() + 180_000)
        main.postDelayed({ requests.remove(token) }, 180_000)
        try { context.startActivity(Intent(context, WritingDictationActivity::class.java).putExtra(TOKEN, token)) }
        catch (error: Exception) { requests.remove(token); throw error }
        return token
    }
    fun request(token: String): Request? {
        val request = requests[token] ?: return null
        if (SystemClock.elapsedRealtime() > request.expiresAt || !request.isValid()) { requests.remove(token); return null }
        return request
    }
    fun cancel(token: String?) { token?.let(requests::remove) }
    fun deliver(token: String, text: String): Boolean {
        val request = request(token) ?: return false
        if (text.isBlank() || text.length > 5_000) return false
        requests.remove(token)
        request.deliver(text)
        return true
    }
    const val TOKEN = "writing_request_token"
}
