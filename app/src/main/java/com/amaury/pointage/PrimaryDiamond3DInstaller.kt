package com.amaury.pointage

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.util.WeakHashMap

/**
 * Branche les trois boutons de pointage permanents sur le moteur OpenGL.
 * Les couleurs sont envoyées directement au shader : aucun filtre n'est posé
 * sur la TextureView, donc le fond reste transparent.
 */
object PrimaryDiamond3DInstaller {
    private const val TAG = "hp_primary_diamond_3d_v2"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()
    private var currentLightAngle = -55f

    fun install(root: View, lightAngle: Float = currentLightAngle) {
        currentLightAngle = lightAngle
        val buttons = ArrayList<Button>(3)
        collectPrimary(root, buttons)
        buttons.forEach { button ->
            val existing = hosts[button]
            if (existing != null) {
                button.alpha = 0f
                existing.setLightAngle(currentLightAngle)
                existing.setBaseColor(colorFor(button))
            } else {
                wrap(button, currentLightAngle)
            }
        }
    }

    fun updateLight(root: View, lightAngle: Float) {
        currentLightAngle = lightAngle
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) {
                host.setBaseColor(colorFor(button))
                host.setLightAngle(lightAngle)
            }
        }
    }

    fun updateAllLight(lightAngle: Float) {
        currentLightAngle = lightAngle
        hosts.entries.toList().forEach { (button, host) ->
            host.setBaseColor(colorFor(button))
            host.setLightAngle(lightAngle)
        }
    }

    private fun collectPrimary(view: View, out: MutableList<Button>) {
        if (view is Button && isPrimary(view) && view.getTag(R.id.true3d_internal_tag) != TAG) out.add(view)
        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) collectPrimary(view.getChildAt(i), out)
        }
    }

    private fun isPrimary(button: Button): Boolean {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return name == "entryButton" || name == "pauseButton" || name == "exitButton"
    }

    private fun colorFor(button: Button): Int {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return when (name) {
            "entryButton" -> Color.rgb(9, 223, 62)
            "pauseButton" -> Color.rgb(246, 108, 6)
            "exitButton" -> Color.rgb(244, 9, 11)
            else -> Color.rgb(45, 135, 235)
        }
    }

    private fun wrap(button: Button, lightAngle: Float) {
        val parent = button.parent as? ViewGroup ?: return
        if (parent is True3DButtonHost) return

        val index = parent.indexOfChild(button)
        val originalLayoutParams = button.layoutParams
        parent.removeViewAt(index)

        val host = True3DButtonHost(button.context)
        host.layoutParams = originalLayoutParams
        host.setTag(R.id.true3d_internal_tag, TAG)
        parent.addView(host, index)

        host.attachButton(button, DiamondTuningStore.load(button.context), lightAngle)
        host.setBaseColor(colorFor(button))
        button.setTag(R.id.true3d_internal_tag, TAG)
        button.alpha = 0f
        hosts[button] = host
    }
}
