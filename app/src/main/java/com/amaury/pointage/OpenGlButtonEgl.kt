package com.amaury.pointage

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Looper

/**
 * Socle EGL partagé destiné à tous les boutons OpenGL de HoraTrack.
 *
 * Le contexte est créé une seule fois sur le thread de rendu partagé. Chaque
 * bouton conserve sa propre EGLSurface, ce qui permet de mutualiser le contexte
 * sans mélanger les états visuels, textures ou matériaux propres à chaque bouton.
 *
 * La destruction d'une surface accepte un nettoyage GPU explicite : il est
 * exécuté pendant que le contexte partagé est encore courant. Toutes les
 * opérations EGL sont volontairement confinées au thread de rendu partagé.
 *
 * Les surfaces créées sont suivies explicitement afin qu'une destruction répétée
 * ou une tentative de rendu sur une surface déjà détruite ne puisse pas perturber
 * les autres boutons qui utilisent le même contexte EGL.
 */
internal object OpenGlButtonEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var configuredDepth = -1
    private val liveSurfaces = HashSet<EGLSurface>()

    fun createSurface(texture: SurfaceTexture, depthBits: Int): EGLSurface {
        checkRenderThread()
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
        check(liveSurfaces.add(surface)) { "Surface EGL du bouton déjà enregistrée" }
        return surface
    }

    fun makeCurrent(surface: EGLSurface) {
        checkRenderThread()
        check(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT)
        check(surface in liveSurfaces) { "Tentative d'utiliser une surface EGL déjà détruite" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "Impossible d'activer le contexte EGL partagé"
        }
    }

    fun swap(surface: EGLSurface) {
        checkRenderThread()
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return
        check(surface in liveSurfaces) { "Tentative de présenter une surface EGL déjà détruite" }
        check(EGL14.eglSwapBuffers(display, surface)) {
            "Impossible de présenter la surface EGL du bouton OpenGL"
        }
    }

    fun destroySurface(
        surface: EGLSurface,
        releaseGpuResources: (() -> Unit)? = null
    ) {
        checkRenderThread()
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return
        if (surface !in liveSurfaces) return

        if (releaseGpuResources != null) {
            makeCurrent(surface)
            releaseGpuResources()
        }

        if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == surface) {
            check(
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            ) { "Impossible de détacher la surface EGL du bouton OpenGL" }
        }

        check(EGL14.eglDestroySurface(display, surface)) {
            "Impossible de détruire la surface EGL du bouton OpenGL"
        }
        liveSurfaces.remove(surface)
    }

    private fun ensureInitialized(depthBits: Int) {
        checkRenderThread()
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

    private fun checkRenderThread() {
        check(Looper.myLooper() === DiamondRenderThread.handler.looper) {
            "Opération EGL hors du thread de rendu OpenGL partagé"
        }
    }
}
