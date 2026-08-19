package com.amaury.pointage

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Boutons Diamant en vraie 3D OpenGL.
 *
 * Géométrie réelle : table supérieure, couronne facettée, biseau, ceinture et pavillon.
 * Le mesh est dimensionné selon le ratio exact de chaque bouton afin de remplir sa surface.
 * Aucun Canvas/GradientDrawable n'est utilisé pour le corps du bouton.
 */
class True3DButtonTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val renderer = CrystalMeshRenderer()
    private val renderThread = HandlerThread("hp-diamond-3d").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private var egl: EglSession? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
    }

    fun setLightAngle(angle: Float) {
        renderer.lightAngle = angle
        requestRender()
    }

    fun setPressedDepth(pressed: Boolean) {
        renderer.pressed = pressed
        requestRender()
    }

    fun setCrystalTuning(value: DiamondTuning) {
        renderer.tuning = value
        requestRender()
    }

    private fun requestRender() = renderHandler.post { drawFrame() }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        renderHandler.post {
            releaseEgl()
            egl = EglSession(surface)
            drawFrame()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderHandler.post { releaseEgl() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderHandler.post {
            releaseEgl()
            renderThread.quitSafely()
        }
    }

    private fun drawFrame() {
        val session = egl ?: return
        session.makeCurrent()
        renderer.draw(surfaceWidth, surfaceHeight)
        session.swap()
    }

    private fun releaseEgl() {
        egl?.release()
        egl = null
    }

    private class EglSession(surfaceTexture: SurfaceTexture) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1))
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surfaceTexture,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE)
        }

        fun makeCurrent() = EGL14.eglMakeCurrent(display, surface, surface, context)
        fun swap() = EGL14.eglSwapBuffers(display, surface)

        fun release() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private class CrystalMeshRenderer {
        var lightAngle = -55f
        var pressed = false
        var tuning = DiamondTuning()

        private var program = 0
        private var shadowProgram = 0
        private var positionLoc = 0
        private var normalLoc = 0
        private var mvpLoc = 0
        private var modelLoc = 0
        private var lightLoc = 0
        private var baseColorLoc = 0
        private var alphaLoc = 0
        private var vertices: FloatBuffer? = null
        private var vertexCount = 0

        fun draw(width: Int, height: Int) {
            if (program == 0) createProgram()
            buildMesh(width, height)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
            val projection = FloatArray(16)
            val view = FloatArray(16)
            val model = FloatArray(16)
            val vp = FloatArray(16)
            val mvp = FloatArray(16)

            // Perspective réelle, mais cadrage serré : le cristal occupe presque tout le bouton.
            Matrix.perspectiveM(projection, 0, 26f, aspect, 0.1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, -2.55f, 4.15f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, pressed.then(.035f, 0f), pressed.then(-.18f, .16f))
            Matrix.rotateM(model, 0, -8.5f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, 2.8f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            val rad = Math.toRadians(lightAngle.toDouble())
            val lx = cos(rad).toFloat()
            val ly = sin(rad).toFloat()

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(lightLoc, lx * 1.2f, -ly * 1.2f, 1.7f)

            val blue = tuning.iceBlue.coerceIn(0f, 1f)
            GLES20.glUniform3f(
                baseColorLoc,
                .28f - blue * .10f,
                .52f + blue * .18f,
                .74f + blue * .22f
            )
            GLES20.glUniform1f(alphaLoc, (.94f - tuning.transparency * .18f).coerceIn(.72f, .97f))

            val buffer = vertices ?: return
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(normalLoc)
            GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(normalLoc)
        }

        private fun createProgram() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, """
                uniform mat4 uMvp;
                uniform mat4 uModel;
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                varying vec3 vNormal;
                varying vec3 vWorld;
                varying vec3 vObject;
                void main() {
                    vec4 world = uModel * vec4(aPosition, 1.0);
                    vWorld = world.xyz;
                    vObject = aPosition;
                    vNormal = normalize(mat3(uModel) * aNormal);
                    gl_Position = uMvp * vec4(aPosition, 1.0);
                }
            """.trimIndent())

            val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
                precision highp float;
                uniform vec3 uLight;
                uniform vec3 uBaseColor;
                uniform float uAlpha;
                varying vec3 vNormal;
                varying vec3 vWorld;
                varying vec3 vObject;

                void main() {
                    vec3 N = normalize(vNormal);
                    vec3 L = normalize(uLight);
                    vec3 V = normalize(vec3(0.0, -2.55, 4.15) - vWorld);
                    vec3 H = normalize(L + V);

                    float ndl = max(dot(N, L), 0.0);
                    float ndv = max(dot(N, V), 0.0);
                    float specSharp = pow(max(dot(N, H), 0.0), 96.0);
                    float specWide = pow(max(dot(N, H), 0.0), 18.0);
                    float fresnel = pow(1.0 - ndv, 3.5);
                    float back = pow(max(dot(-N, L), 0.0), 2.0);

                    // Absorption interne : centre plus profond, arêtes plus lumineuses.
                    float depth = clamp(1.0 - abs(vObject.z) * .72, 0.0, 1.0);
                    vec3 body = uBaseColor * (0.20 + ndl * 0.50 + depth * 0.16);

                    // Réflexions blanches et bleutées de type diamant.
                    vec3 reflection = vec3(1.0) * specSharp * 1.75
                                    + vec3(.62,.83,1.0) * specWide * .34
                                    + vec3(.32,.66,1.0) * fresnel * .95;

                    // Dispersion chromatique légère sur les arêtes (feu du diamant).
                    float edge = pow(fresnel, 1.5);
                    float phase = vObject.x * 5.1 + vObject.y * 7.7;
                    vec3 fire = vec3(
                        .95 + .05 * sin(phase),
                        .80 + .20 * sin(phase + 2.094),
                        .92 + .08 * sin(phase + 4.188)
                    ) * edge * .28;

                    vec3 internalLight = vec3(.44,.70,1.0) * back * .28;
                    vec3 color = body + reflection + fire + internalLight;
                    color = color / (color + vec3(.92));

                    float alpha = clamp(uAlpha + fresnel * .06 + specSharp * .03, .70, .98);
                    gl_FragColor = vec4(color, alpha);
                }
            """.trimIndent())

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }

            positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            normalLoc = GLES20.glGetAttribLocation(program, "aNormal")
            mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            modelLoc = GLES20.glGetUniformLocation(program, "uModel")
            lightLoc = GLES20.glGetUniformLocation(program, "uLight")
            baseColorLoc = GLES20.glGetUniformLocation(program, "uBaseColor")
            alphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun buildMesh(width: Int, height: Int) {
            val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()

            // IMPORTANT : largeur proportionnelle à l'aspect du vrai bouton.
            // C'était la cause principale des petits losanges visibles au centre auparavant.
            val halfH = .92f
            val halfW = (halfH * aspect).coerceIn(.92f, 7.2f)
            val cornerX = (halfH * .58f).coerceAtMost(halfW * .24f)
            val cornerY = halfH * .34f

            val facet = tuning.facetDepth.coerceIn(0f, 1f)
            val bevel = tuning.bevel.coerceIn(0f, 1f)

            val girdleZ = -.02f
            val topZ = .24f + bevel * .13f
            val tableZ = topZ + .13f + facet * .08f
            val bottomZ = -.26f - facet * .17f

            val outer = octagon(halfW, halfH, cornerX, cornerY, girdleZ)
            val crown = octagon(
                halfW - halfH * (.16f + bevel * .06f),
                halfH * (.70f - bevel * .04f),
                cornerX * .78f,
                cornerY * .78f,
                topZ
            )
            val table = octagon(
                halfW - halfH * (.38f + facet * .04f),
                halfH * (.47f - facet * .03f),
                cornerX * .56f,
                cornerY * .52f,
                tableZ
            )
            val lower = Array(8) { i -> floatArrayOf(outer[i][0] * .92f, outer[i][1] * .90f, bottomZ) }

            val data = ArrayList<Float>(8 * 6 * 6 * 4)

            fun addTri(a: FloatArray, b: FloatArray, c: FloatArray) {
                val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
                val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.00001f)
                nx /= l; ny /= l; nz /= l
                arrayOf(a, b, c).forEach { v ->
                    data.add(v[0]); data.add(v[1]); data.add(v[2])
                    data.add(nx); data.add(ny); data.add(nz)
                }
            }

            fun connectRings(a: Array<FloatArray>, b: Array<FloatArray>, flip: Boolean = false) {
                for (i in 0 until 8) {
                    val n = (i + 1) % 8
                    if (!flip) {
                        addTri(a[i], a[n], b[n])
                        addTri(a[i], b[n], b[i])
                    } else {
                        addTri(a[i], b[n], a[n])
                        addTri(a[i], b[i], b[n])
                    }
                }
            }

            // Face/table plane, entourée d'une couronne réellement inclinée.
            val tableCenter = floatArrayOf(0f, 0f, tableZ)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                addTri(table[i], table[n], tableCenter)
            }

            // Couronne + biseau + parois externes.
            connectRings(table, crown)
            connectRings(crown, outer)

            // Pavillon inférieur : volume complet, visible sur l'inclinaison perspective.
            connectRings(outer, lower)
            val bottomCenter = floatArrayOf(0f, 0f, bottomZ - .05f)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                addTri(lower[n], lower[i], bottomCenter)
            }

            vertexCount = data.size / 6
            vertices = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    data.forEach { put(it) }
                    position(0)
                }
        }

        private fun octagon(
            halfW: Float,
            halfH: Float,
            cutX: Float,
            cutY: Float,
            z: Float
        ) = arrayOf(
            floatArrayOf(-halfW + cutX,  halfH, z),
            floatArrayOf( halfW - cutX,  halfH, z),
            floatArrayOf( halfW,  halfH - cutY, z),
            floatArrayOf( halfW, -halfH + cutY, z),
            floatArrayOf( halfW - cutX, -halfH, z),
            floatArrayOf(-halfW + cutX, -halfH, z),
            floatArrayOf(-halfW, -halfH + cutY, z),
            floatArrayOf(-halfW,  halfH - cutY, z)
        )

        private fun Boolean.then(yes: Float, no: Float) = if (this) yes else no
    }
}

/**
 * Conteneur : le mesh 3D est derrière, le vrai Button Android reste devant pour le texte,
 * l'accessibilité et le clic. Le texte n'est jamais rasterisé dans le moteur 3D.
 */
class True3DButtonHost(context: Context) : FrameLayout(context) {
    private val surface = True3DButtonTextureView(context)
    lateinit var button: Button
        private set

    init {
        clipChildren = false
        clipToPadding = false
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun attachButton(value: Button, tuning: DiamondTuning, lightAngle: Float) {
        button = value
        surface.setCrystalTuning(tuning)
        surface.setLightAngle(lightAngle)
        addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        value.background = null
        value.backgroundTintList = null
        value.stateListAnimator = null
        value.elevation = 0f
        value.translationZ = 0f
        value.setPadding(value.paddingLeft, value.paddingTop, value.paddingRight, value.paddingBottom)
    }

    fun setLightAngle(angle: Float) = surface.setLightAngle(angle)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> surface.setPressedDepth(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> surface.setPressedDepth(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}

object True3DButtonInstaller {
    private const val WRAPPED_TAG = "hp_true_3d_wrapped_v2"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()

    fun install(root: View, lightAngle: Float) {
        val buttons = ArrayList<Button>()
        collectButtons(root, buttons)
        buttons.forEach { wrap(it, lightAngle) }
    }

    fun updateLight(root: View, lightAngle: Float) {
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) host.setLightAngle(lightAngle)
        }
    }

    private fun collectButtons(view: View, out: MutableList<Button>) {
        if (view is Button && view.getTag(R.id.true3d_internal_tag) != WRAPPED_TAG) out.add(view)
        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out)
        }
    }

    private fun wrap(button: Button, lightAngle: Float) {
        if (hosts.containsKey(button)) return
        val parent = button.parent as? ViewGroup ?: return
        if (parent is True3DButtonHost) return

        val index = parent.indexOfChild(button)
        val oldParams = button.layoutParams
        parent.removeViewAt(index)

        val host = True3DButtonHost(button.context)
        host.layoutParams = oldParams
        host.setTag(R.id.true3d_internal_tag, WRAPPED_TAG)
        parent.addView(host, index)
        host.attachButton(button, DiamondTuningStore.load(button.context), lightAngle)
        button.setTag(R.id.true3d_internal_tag, WRAPPED_TAG)
        hosts[button] = host
    }
}
