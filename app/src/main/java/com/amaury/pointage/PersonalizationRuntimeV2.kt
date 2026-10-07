package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.firebase.auth.FirebaseAuth
import java.util.WeakHashMap

/** Applies to native view trees, including new views, without changing business values. */
object PersonalizationRuntimeV2 : Application.ActivityLifecycleCallbacks {
    private data class TextState(var basePx: Float, var lastPx: Float, val originalHeight: Int?, val originalMinimumHeight: Int,
        val maxLines: Int, val horizontal: Boolean, var colors: android.content.res.ColorStateList,
        var hint: android.content.res.ColorStateList?, var background: android.graphics.drawable.Drawable?,
        var tint: android.content.res.ColorStateList?, var protected: Boolean = false,
        var resized: Boolean = false, var expandedLines: Boolean = false)
    private data class Screen(val listener: ViewTreeObserver.OnGlobalLayoutListener, val update: Runnable)
    private data class DialogState(val owner: String, val screen: Screen)
    private val dialogs = WeakHashMap<AlertDialog, DialogState>()
    private val texts = WeakHashMap<TextView, TextState>()
    private val screens = WeakHashMap<Activity, Screen>()
    private val readerActions = WeakHashMap<TextView, Int>()
    private var app: Application? = null

    fun install(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(this)
        runCatching { FirebaseAuth.getInstance().addAuthStateListener { refresh() } }
    }
    fun refresh() {
        refreshDialogs()
        screens.keys.toList().forEach { activity ->
            if (!activity.isDestroyed) {
                releaseProtection(activity.window.decorView)
                AppearanceManager.apply(activity)
                ThemeFrameStyler.apply(activity.window.decorView)
                activity.window.decorView.invalidate()
            }
        }
    }
    fun track(dialog: AlertDialog): AlertDialog {
        if (dialogs.containsKey(dialog)) return dialog
        val root = dialog.window?.decorView ?: return dialog
        val update = Runnable { if (dialog.isShowing) apply(root) }
        val observer = ViewTreeObserver.OnGlobalLayoutListener {
            root.removeCallbacks(update); root.postDelayed(update, 120)
        }
        dialogs[dialog] = DialogState(PersonalizationStoreV2.accountScope(), Screen(observer, update))
        root.viewTreeObserver.addOnGlobalLayoutListener(observer)
        // Do not replace the caller's dismissal callback (writing sessions have their own cleanup).
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                root.removeCallbacks(update)
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(observer)
                forget(root); dialogs.remove(dialog)
                root.removeOnAttachStateChangeListener(this)
            }
        })
        apply(root)
        return dialog
    }
    private fun refreshDialogs() {
        val owner = PersonalizationStoreV2.accountScope()
        dialogs.entries.toList().forEach { (dialog, state) ->
            if (owner != state.owner) dialog.dismiss()
            else dialog.window?.decorView?.let(::apply)
        }
    }
    private fun forget(view: View) {
        if (view is android.widget.ImageView) ContentImageReaderV2.forget(view)
        if (view is TextView) { texts.remove(view); readerActions.remove(view) }
        if (view is ViewGroup) for (i in 0 until view.childCount) forget(view.getChildAt(i))
    }
    private fun releaseProtection(view: View) {
        if (view is TextView) texts[view]?.takeIf { it.protected }?.let {
            view.background = it.background; view.backgroundTintList = it.tint
            view.setTextColor(it.colors); view.setHintTextColor(it.hint); it.protected = false
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) releaseProtection(view.getChildAt(i))
    }
    fun reducedMotion(context: android.content.Context): Boolean = PersonalizationStoreV2.read(context).effectiveReduceMotion

    fun apply(root: View) {
        val profile = PersonalizationStoreV2.read(root.context)
        val protectImage = root.context.getSharedPreferences(AppThemeCatalog.PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean("custom_image_bg", false)
        walk(root, profile, protectImage)
    }
    private fun walk(view: View, profile: PersonalizationProfileV2, protectImage: Boolean) {
        if (view.tag == "personalization_reader_v2") return
        if (view is android.widget.ImageView) ContentImageReaderV2.attach(view)
        if (view is TextView) {
            val prior = texts[view]
            val state = prior ?: TextState(view.textSize, view.textSize, view.layoutParams?.height, view.minimumHeight,
                view.maxLines, if (android.os.Build.VERSION.SDK_INT >= 29) view.isHorizontallyScrollable else view.maxLines == 1, view.textColors, view.hintTextColors,
                view.background, view.backgroundTintList).also { texts[view] = it }
            if (prior != null && kotlin.math.abs(view.textSize - state.lastPx) > .1f) state.basePx = view.textSize
            // Base is captured before scaling; reapplying must never multiply an already scaled font.
            val target = state.basePx * profile.textScale
            if (kotlin.math.abs(view.textSize - target) > .1f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            state.lastPx = target
            if (profile.textScale > 1f) {
                if (view !is EditText) {
                    if (view.maxLines != Int.MAX_VALUE) {
                        view.maxLines = Int.MAX_VALUE
                        view.setHorizontallyScrolling(false)
                        state.expandedLines = true
                    }
                }
                view.layoutParams?.let { params ->
                    if (params.height > 0) {
                        view.minimumHeight = params.height
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        state.resized = true
                        view.layoutParams = params
                    }
                }
            } else {
                if (view !is EditText && state.expandedLines) {
                    if (view.maxLines != state.maxLines) {
                        view.maxLines = state.maxLines
                        view.setHorizontallyScrolling(state.horizontal)
                    }
                    state.expandedLines = false
                }
                if (state.resized && state.originalHeight != null) {
                    view.layoutParams?.let { params -> params.height = state.originalHeight; view.layoutParams = params }
                    view.minimumHeight = state.originalMinimumHeight
                    state.resized = false
                }
            }
            // Refresh the baseline whenever the effective theme is visible, not only at first sight.
            if (!state.protected) {
                state.background = view.background; state.tint = view.backgroundTintList
                state.colors = view.textColors; state.hint = view.hintTextColors
            }
            // An image is not a request to replace the theme of every control. Tabs are
            // TextViews too; their drawable, tint and selected/pressed colors belong to
            // navigation. Only bare, non-interactive labels need automatic backing.
            // Inspect the saved theme background, not the backing applied on the last walk.
            val bareLabel = view !is Button && view !is EditText && !view.isClickable &&
                !view.hasOnClickListeners() && !view.isFocusable &&
                (state.background == null ||
                    (state.background as? android.graphics.drawable.ColorDrawable)?.color == Color.TRANSPARENT)
            val needsSupport = profile.highContrast || (protectImage && bareLabel)
            if (needsSupport) {
                HighContrastTextStyleV2.apply(view)
                state.protected = true
            } else if (state.protected) {
                view.background = state.background
                view.backgroundTintList = state.tint
                view.setTextColor(state.colors)
                view.setHintTextColor(state.hint)
                state.protected = false
            }
            if (view !is EditText && view !is Button && view.text.isNotBlank() && readerActions[view] == null) {
                val action = ViewCompat.addAccessibilityAction(view, "Agrandir pour lire") { _, _ ->
                    showReader(view); true
                }
                readerActions[view] = action
                if (!view.isLongClickable && !view.hasOnClickListeners()) {
                    view.setOnLongClickListener { showReader(view); true }
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), profile, protectImage)
    }
    private fun showReader(source: TextView) {
        val context = source.context
        val profile = PersonalizationStoreV2.read(context)
        val owner = PersonalizationStoreV2.accountScope()
        var scale = profile.readerScale
        val body = LinearLayout(context).apply {
            tag = "personalization_reader_v2"
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val text = TextView(context).apply {
            this.text = source.text
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        }
        fun resize(value: Float) {
            scale = value.coerceIn(1f, 4f)
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        }
        val controls = LinearLayout(context)
        fun control(label: String, click: () -> Unit) = Button(context).apply {
            this.text = label; isAllCaps = false; setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        controls.addView(control("Réduire") { resize(scale - .25f) })
        controls.addView(control("Agrandir") { resize(scale + .25f) })
        controls.addView(control("100 %") { resize(1f) })
        body.addView(controls)
        val speechStatus = TextView(context).apply {
            this.text = "Lecture vocale uniquement sur demande, avec une voix hors ligne installée."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val speech = OfflineReaderSpeechV2(context, owner) { speechStatus.text = it }
        val speechControls = LinearLayout(context)
        speechControls.addView(control("Lire") {
            val content = text.text
            val start = minOf(text.selectionStart, text.selectionEnd)
            val end = maxOf(text.selectionStart, text.selectionEnd)
            val hasSelection = start >= 0 && end > start && end <= content.length
            if (hasSelection && (!com.amaury.pointage.writing.WritingEngine.safeBoundary(content.toString(), start) ||
                    !com.amaury.pointage.writing.WritingEngine.safeBoundary(content.toString(), end))) {
                speechStatus.text = "Sélectionne des caractères complets avant de lancer la lecture."
                return@control
            }
            val selected = if (hasSelection) content.subSequence(start, end) else content
            speech.read(selected, java.util.Locale.getDefault())
        })
        speechControls.addView(control("Arrêter") { speech.stop() })
        body.addView(speechControls)
        body.addView(speechStatus)
        val scroll = ScrollView(context).apply { addView(text) }
        val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                resize(scale * detector.scaleFactor); return true
            }
        })
        scroll.setOnTouchListener { _, event -> detector.onTouchEvent(event); detector.isInProgress }
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = AlertDialog.Builder(context).setTitle("Lecture agrandie").setView(body)
            .setPositiveButton("Fermer", null).setNeutralButton("Mémoriser ce zoom") { _, _ ->
                if (owner == PersonalizationStoreV2.accountScope() && !PersonalizationStoreV2.update(context, owner) { it.copy(readerScale = scale) })
                    android.widget.Toast.makeText(context, "Enregistrement impossible", android.widget.Toast.LENGTH_LONG).show()
            }.create()
        dialog.show()
        // Keep existing dismissal/layout callbacks; release speech when the dialog leaves the window.
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                speech.dispose()
                view.removeOnAttachStateChangeListener(this)
            }
        })
        track(dialog)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * .85f).toInt())
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val root = activity.window.decorView
        val update = Runnable { if (!activity.isDestroyed) apply(root) }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            root.removeCallbacks(update); root.postDelayed(update, 120)
        }
        screens[activity] = Screen(listener, update)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.post(update)
    }
    override fun onActivityResumed(activity: Activity) { apply(activity.window.decorView) }
    override fun onActivityDestroyed(activity: Activity) {
        forget(activity.window.decorView)
        screens.remove(activity)?.let {
            activity.window.decorView.removeCallbacks(it.update)
            activity.window.decorView.viewTreeObserver.takeIf { tree -> tree.isAlive }?.removeOnGlobalLayoutListener(it.listener)
        }
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
