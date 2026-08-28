package com.amaury.pointage

import android.os.Handler
import android.os.HandlerThread

/**
 * File de rendu OpenGL partagée par tous les diamants 3D.
 *
 * Le thread vit pendant toute la durée du processus HoraTrack afin d'éviter
 * de créer/détruire un HandlerThread pour chaque bouton. Les contextes EGL
 * restent volontairement indépendants à cette étape.
 */
internal object DiamondRenderThread {
    private val thread = HandlerThread("horatrack-diamond-3d-render").apply { start() }

    val handler: Handler = Handler(thread.looper)
}
