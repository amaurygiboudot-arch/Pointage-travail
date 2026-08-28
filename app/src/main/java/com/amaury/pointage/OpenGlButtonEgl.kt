package com.amaury.pointage

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * Socle EGL partagé destiné à tous les boutons OpenGL de HoraTrack.
 *
 * Le contexte est créé une seule fois sur le thread de rendu partagé. Chaque
 * bouton conserve sa propre EGLSurface, ce qui permet de mutualiser le contexte
 * sans mélanger les états visuels, textures ou matériaux propres à chaque bouton.
 *
 * Cette classe n'est pas encore branchée au renderer des diamants : elle prépare
 * la migration contrôlée afin de conserver strictement leur rendu actuel.
 */
internal object OpenGlButtonEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var configuredDepth = -1

    fun createSurface(texture: SurfaceTexture, depthBits: Int): EGLSurface {
        ensureInitialized(depthBits)
        val eglConfig = checkNotNull(config)
        val surface = EGL14.eglCreateWindowSurface(
            display,
            eglConfig,
            texture,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Impossible de créer la surface EGL du bouton OpenGL" }
        return surface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "Impossible d'activer le contexte EGL partagé"
        }
    }

    fun swap(surface: EGLSurface) {
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglSwapBuffers(display, surface)
    }

    fun destroySurface(surface: EGLSurface) {
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return
        if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == surface) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }
        EGL14.eglDestroySurface(display, surface)
    }

    private fun ensureInitialized(depthBits: Int) {
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) return

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))

        configuredDepth = depthBits.coerceIn(16, 24)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, configuredDepth,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = checkNotNull(configs[0])

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Impossible de créer le contexte EGL partagé" }
    }
}
