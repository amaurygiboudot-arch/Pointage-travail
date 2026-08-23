package com.amaury.pointage

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.util.WeakHashMap

/**
 * Branche les trois boutons de pointage permanents sur le moteur OpenGL déjà
 * utilisé par le thème Diamant. Le bouton Android original reste présent pour
 * conserver son id et ses listeners, mais son dessin 2D est masqué.
 */
object PrimaryDiamond3DInstaller {
    private const val TAG = "hp_primary_diamond_3d_v1"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()

    fun install(root: View, lightAngle: Float) {
        val buttons = ArrayList<Button>(3)
        collectPrimary(root, buttons)
        buttons.forEach { button ->
            val existing = hosts[button]
            if (existing != null) {
                button.alpha = 0f
                existing.setLightAngle(lightAngle)
            } else {
                wrap(button, lightAngle)
            }
        }
    }

    fun updateLight(root: View, lightAngle: Float) {
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) {
                host.setLightAngle(lightAngle)
            }
        }
    }

    private fun collectPrimary(view: View, out: MutableList<Button>) {
        if (view is Button && isPrimary(view) && view.getTag(R.id.true3d_internal_tag) != TAG) {
            out.add(view)
        }
        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) collectPrimary(view.getChildAt(i), out)
        }
    }

    private fun isPrimary(button: Button): Boolean {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return name == "entryButton" || name == "pauseButton" || name == "exitButton"
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
        button.setTag(R.id.true3d_internal_tag, TAG)
        button.alpha = 0f

        // Le premier enfant du host est la TextureView OpenGL du moteur 3D.
        val surface = host.getChildAt(0) as? True3DButtonTextureView
        surface?.let { applyPermanentColor(it, button) }

        hosts[button] = host
    }

    private fun applyPermanentColor(surface: True3DButtonTextureView, button: Button) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        val tint = when (name) {
            "entryButton" -> Color.rgb(24, 220, 82)
            "pauseButton" -> Color.rgb(255, 137, 28)
            "exitButton" -> Color.rgb(238, 32, 58)
            else -> return
        }

        // BlendMode.COLOR garde la luminance / les éclats blancs calculés par
        // le shader OpenGL et ne remplace que la teinte générale de la pierre.
        surface.setRenderEffect(
            RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(tint, BlendMode.COLOR)
            )
        )
    }
}
