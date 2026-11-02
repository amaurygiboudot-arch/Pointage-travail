package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import com.amaury.pointage.v2.SalaryAccountScope

/** A salary screen keeps the session in which it was opened, including delayed callbacks. */
internal object SalaryAccountUi {
    private const val OWNER = "salary_session_owner"
    private const val GENERATION = "salary_session_generation"
    private const val PROCESS = "salary_session_process"

    fun activity(context: Context): Activity? {
        var current = context
        val seen = mutableSetOf<Context>()
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    fun current(context: Context): Boolean =
        SalaryAccountScope.isCurrent(SalaryAccountScope.lease(context)) && SalaryAccountScope.available(context)

    fun attach(intent: Intent, lease: SalaryAccountScope.Lease): Intent = intent.apply {
        putExtra(OWNER, lease.scope)
        putExtra(GENERATION, lease.generation)
        putExtra(PROCESS, lease.processId)
    }

    fun hasSession(intent: Intent): Boolean =
        intent.hasExtra(OWNER) || intent.hasExtra(GENERATION) || intent.hasExtra(PROCESS)

    fun read(intent: Intent): SalaryAccountScope.Lease? {
        if (!intent.hasExtra(OWNER) || !intent.hasExtra(GENERATION) || !intent.hasExtra(PROCESS)) return null
        val owner = intent.getStringExtra(OWNER) ?: return null
        val process = intent.getStringExtra(PROCESS)?.takeIf { it.isNotBlank() } ?: return null
        val lease = SalaryAccountScope.Lease(owner, intent.getLongExtra(GENERATION, -1L), process)
        return lease.takeIf(SalaryAccountScope::isCurrent)
    }

    fun save(state: Bundle, lease: SalaryAccountScope.Lease) {
        state.putString(OWNER, lease.scope)
        state.putLong(GENERATION, lease.generation)
        state.putString(PROCESS, lease.processId)
    }

    fun read(state: Bundle): SalaryAccountScope.Lease? {
        if (!state.containsKey(OWNER) || !state.containsKey(GENERATION) || !state.containsKey(PROCESS)) return null
        val owner = state.getString(OWNER) ?: return null
        val process = state.getString(PROCESS)?.takeIf { it.isNotBlank() } ?: return null
        return SalaryAccountScope.Lease(owner, state.getLong(GENERATION, -1L), process)
            .takeIf(SalaryAccountScope::isCurrent)
    }

    /** Only frozen PDFs may be reverified after process death; no form or authorization is resumed. */
    fun frozenPdfResume(state: Bundle, ownerUid: String?): SalaryAccountScope.Lease? {
        read(state)?.let { return it }
        val current = SalaryAccountScope.capture()
        val previousProcess = state.getString(PROCESS) ?: return null
        if (previousProcess == current.processId || ownerUid.isNullOrBlank()) return null
        return current.takeIf {
            it.scope == "uid:$ownerUid" && state.getString(OWNER) == it.scope && SalaryAccountScope.isCurrent(it)
        }
    }

    fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else Handler(Looper.getMainLooper()).post(action)
    }

    /** Covers nested confirmation dialogs as well as their parent salary screen. */
    fun dialog(context: Context): AlertDialog.Builder = object : AlertDialog.Builder(context) {
        override fun create(): AlertDialog = protect(super.create(), context)
    }

    fun <T : Dialog> protect(dialog: T, context: Context): T = dialog.also {
            val lease = SalaryAccountScope.lease(context)
            val decor = dialog.window?.decorView ?: return@also
            fun closeIfStale() {
                if (!SalaryAccountScope.isCurrent(lease)) {
                    decor.visibility = View.INVISIBLE
                    dialog.dismiss()
                }
            }
            val changed: () -> Unit = { onMain { closeIfStale() } }
            val draw = ViewTreeObserver.OnPreDrawListener {
                closeIfStale()
                SalaryAccountScope.isCurrent(lease)
            }
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    SalaryAccountScope.addListener(changed)
                    view.viewTreeObserver.addOnPreDrawListener(draw)
                    closeIfStale()
                }
                override fun onViewDetachedFromWindow(view: View) {
                    SalaryAccountScope.removeListener(changed)
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(draw)
                }
            })
    }
}

/** Bound once. An old view is never rebound to the next account. */
open class SalaryAccountLayout @JvmOverloads constructor(
    hostContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(SalaryAccountScope.bindContext(hostContext), attrs, defStyleAttr) {
    private val owner = SalaryAccountScope.lease(context)
    private var redacted = false
    private val accountChanged: () -> Unit = { SalaryAccountUi.onMain { redactIfStale() } }
    private val accountDrawCheck = ViewTreeObserver.OnPreDrawListener {
        redactIfStale()
        // The observer belongs to the whole window: hide this view without starving its other tabs.
        true
    }

    protected fun accountCurrent(): Boolean = SalaryAccountScope.isCurrent(owner) &&
        SalaryAccountScope.available(context)

    private fun redactIfStale() {
        if (!redacted && !SalaryAccountScope.isCurrent(owner)) {
            // removeAllViews requests layout even when already empty. Repeating it from
            // pre-draw would schedule another frame forever after this session expires.
            redacted = true
            visibility = GONE
            isEnabled = false
            removeAllViews()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SalaryAccountScope.addListener(accountChanged)
        viewTreeObserver.addOnPreDrawListener(accountDrawCheck)
        redactIfStale()
    }

    override fun onDetachedFromWindow() {
        SalaryAccountScope.removeListener(accountChanged)
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(accountDrawCheck)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) redactIfStale()
    }
}

/** Activities receive an explicit launch session; a process restart requires reopening the screen. */
open class SalaryAccountActivity : Activity(), SalaryAccountScope.LeaseOwner {
    final override var salaryAccountLease: SalaryAccountScope.Lease? = null
        private set
    private val accountChanged: () -> Unit = { SalaryAccountUi.onMain {
        if (!accountReady()) onSalaryAccountInvalidated()
    } }

    protected fun accountReady(): Boolean = !isFinishing && !isDestroyed &&
        salaryAccountLease?.let(SalaryAccountScope::isCurrent) == true && SalaryAccountScope.available(this)

    protected open fun initialAccountLease(state: Bundle?): SalaryAccountScope.Lease? =
        if (state == null) SalaryAccountUi.read(intent) else SalaryAccountUi.read(state)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        salaryAccountLease = initialAccountLease(savedInstanceState)
        if (!accountReady()) {
            onSalaryAccountInvalidated()
            return
        }
        SalaryAccountScope.addListener(accountChanged)
    }

    override fun getApplicationContext(): Context =
        SalaryAccountScope.bindContext(super.getApplicationContext(), SalaryAccountScope.lease(this))

    override fun onResume() {
        super.onResume()
        if (!accountReady()) onSalaryAccountInvalidated()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        salaryAccountLease?.let { SalaryAccountUi.save(outState, it) }
        super.onSaveInstanceState(outState)
    }

    protected open fun onSalaryAccountInvalidated() {
        window?.decorView?.visibility = View.INVISIBLE
        finish()
    }

    override fun onDestroy() {
        SalaryAccountScope.removeListener(accountChanged)
        super.onDestroy()
    }
}
