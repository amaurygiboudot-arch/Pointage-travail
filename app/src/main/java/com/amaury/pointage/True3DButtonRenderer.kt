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

/**
 * Vrai rendu 3D des boutons Diamant.
 * Le volume est un mesh OpenGL extrudé (face supérieure + côtés + fond),
 * éclairé en perspective. Aucun dessin Canvas n'est utilisé pour le bouton.
 */
class True3DButtonTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val renderer = CrystalMeshRenderer()
    private val renderThread = HandlerThread("hp-3d-button").apply { start() }
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

    private fun requestRender() {
        renderHandler.post { drawFrame() }
    }

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

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0)
            val config = configs[0]!!

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, surfaceAttribs, 0)
            check(surface != EGL14.EGL_NO_SURFACE)
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private class CrystalMeshRenderer {
        var lightAngle: Float = -55f
        var pressed: Boolean = false
        var tuning: DiamondTuning = DiamondTuning()

        private var program = 0
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

            Matrix.perspectiveM(projection, 0, 32f, aspect, 0.1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, -2.2f, 3.6f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, if (pressed) 0.05f else 0f, if (pressed) -0.18f else 0.12f)
            Matrix.rotateM(model, 0, -11f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, 4f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            val rad = Math.toRadians(lightAngle.toDouble())
            val lx = cos(rad).toFloat()
            val ly = sin(rad).toFloat()

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(lightLoc, lx, -ly, 1.35f)

            val blue = tuning.iceBlue.coerceIn(0f, 1f)
            GLES20.glUniform3f(baseColorLoc, 0.42f - blue * .20f, 0.68f + blue * .12f, 0.88f + blue * .10f)
            GLES20.glUniform1f(alphaLoc, (0.88f - tuning.transparency * .34f).coerceIn(.48f, .94f))

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
            val vertexShader = compile(GLES20.GL_VERTEX_SHADER, """
                uniform mat4 uMvp;
                uniform mat4 uModel;
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                varying vec3 vNormal;
                varying vec3 vWorld;
                void main() {
                    vec4 world = uModel * vec4(aPosition, 1.0);
                    vWorld = world.xyz;
                    vNormal = normalize(mat3(uModel) * aNormal);
                    gl_Position = uMvp * vec4(aPosition, 1.0);
                }
            """.trimIndent())
            val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform vec3 uLight;
                uniform vec3 uBaseColor;
                uniform float uAlpha;
                varying vec3 vNormal;
                varying vec3 vWorld;
                void main() {
                    vec3 N = normalize(vNormal);
                    vec3 L = normalize(uLight);
                    vec3 V = normalize(vec3(0.0, -2.2, 3.6) - vWorld);
                    vec3 H = normalize(L + V);
                    float diffuse = max(dot(N, L), 0.0);
                    float spec = pow(max(dot(N, H), 0.0), 42.0);
                    float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);
                    float internal = pow(max(dot(-N, L), 0.0), 2.0) * 0.22;
                    vec3 cold = mix(uBaseColor * 0.22, uBaseColor, 0.34 + diffuse * 0.66);
                    vec3 color = cold + vec3(spec * 1.35) + vec3(0.35,0.65,1.0) * fresnel * 0.55 + vec3(internal);
                    float alpha = clamp(uAlpha + fresnel * .14 + spec * .08, .35, .98);
                    gl_FragColor = vec4(color, alpha);
                }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
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
            val halfW = if (aspect > 1f) 1.55f else 1.0f
            val halfH = if (aspect > 1f) (halfW / aspect).coerceAtLeast(.45f) else 1.0f
            val cutX = halfW * .18f
            val cutY = halfH * .28f
            val topZ = .28f + tuning.bevel.coerceIn(0f,1f) * .16f
            val bottomZ = -.24f - tuning.facetDepth.coerceIn(0f,1f) * .16f

            val p = arrayOf(
                floatArrayOf(-halfW + cutX, halfH, topZ),
                floatArrayOf( halfW - cutX, halfH, topZ),
                floatArrayOf( halfW, halfH - cutY, topZ),
                floatArrayOf( halfW, -halfH + cutY, topZ),
                floatArrayOf( halfW - cutX, -halfH, topZ),
                floatArrayOf(-halfW + cutX, -halfH, topZ),
                floatArrayOf(-halfW, -halfH + cutY, topZ),
                floatArrayOf(-halfW, halfH - cutY, topZ)
            )
            val b = Array(8) { i -> floatArrayOf(p[i][0] * .92f, p[i][1] * .92f, bottomZ) }
            val data = ArrayList<Float>(8 * 6 * 6)

            fun tri(a: FloatArray, c: FloatArray, d: FloatArray, nx: Float, ny: Float, nz: Float) {
                arrayOf(a,c,d).forEach { v ->
                    data.add(v[0]); data.add(v[1]); data.add(v[2]); data.add(nx); data.add(ny); data.add(nz)
                }
            }

            // Surface supérieure réelle, composée de facettes triangulaires convergeant vers la table.
            val center = floatArrayOf(0f, 0f, topZ + .10f + tuning.facetDepth * .10f)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                val ax = p[i][0]; val ay = p[i][1]; val bx = p[n][0]; val by = p[n][1]
                val ux = bx - ax; val uy = by - ay; val uz = 0f
                val vx = center[0] - ax; val vy = center[1] - ay; val vz = center[2] - p[i][2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(.0001f)
                nx/=len; ny/=len; nz/=len
                tri(p[i], p[n], center, nx, ny, nz)
            }

            // Côtés extrudés : c'est eux qui donnent le vrai volume hors de l'écran.
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                val ex = p[n][0] - p[i][0]
                val ey = p[n][1] - p[i][1]
                val len = kotlin.math.sqrt(ex*ex + ey*ey).coerceAtLeast(.0001f)
                val nx = ey/len
                val ny = -ex/len
                tri(p[i], b[i], b[n], nx, ny, .08f)
                tri(p[i], b[n], p[n], nx, ny, .08f)
            }

            // Fond légèrement fermé pour renforcer les réflexions internes.
            val bottomCenter = floatArrayOf(0f,0f,bottomZ)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                tri(b[n], b[i], bottomCenter, 0f, 0f, -1f)
            }

            vertexCount = data.size / 6
            vertices = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                data.forEach { put(it) }
                position(0)
            }
        }
    }
}

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
        value.stateListAnimator = null
        value.elevation = 0f
        value.translationZ = 0f
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
    private const val WRAPPED_TAG = "hp_true_3d_wrapped"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()

    fun install(root: View, lightAngle: Float) {
        val buttons = ArrayList<Button>()
        collectButtons(root, buttons)
        buttons.forEach { button -> wrap(button, lightAngle) }
    }

    fun updateLight(root: View, lightAngle: Float) {
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) host.setLightAngle(lightAngle)
        }
    }

    private fun collectButtons(view: View, out: MutableList<Button>) {
        if (view is Button && view.getTag(com.amaury.pointage.R.id.true3d_internal_tag) != WRAPPED_TAG) out.add(view)
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
        host.setTag(com.amaury.pointage.R.id.true3d_internal_tag, WRAPPED_TAG)
        parent.addView(host, index)
        host.attachButton(button, DiamondTuningStore.load(button.context), lightAngle)
        hosts[button] = host
    }
}
