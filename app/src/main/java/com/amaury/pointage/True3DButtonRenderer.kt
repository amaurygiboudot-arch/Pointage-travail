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
 * Géométrie : table, couronne, biseau, ceinture et pavillon.
 * Les côtés utilisent un matériau plus réfléchissant afin de retrouver
 * les arêtes blanches très brillantes d'un diamant taillé.
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
        private var positionLoc = 0
        private var normalLoc = 0
        private var regionLoc = 0
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

            Matrix.perspectiveM(projection, 0, 24f, aspect, 0.1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, -2.72f, 4.3f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, if (pressed) .035f else 0f, if (pressed) -.16f else .18f)
            Matrix.rotateM(model, 0, -8.2f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, 2.4f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            val rad = Math.toRadians(lightAngle.toDouble())
            val lx = cos(rad).toFloat()
            val ly = sin(rad).toFloat()

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(lightLoc, lx * 1.35f, -ly * 1.35f, 1.9f)

            val blue = tuning.iceBlue.coerceIn(0f, 1f)
            GLES20.glUniform3f(
                baseColorLoc,
                .20f - blue * .05f,
                .44f + blue * .18f,
                .68f + blue * .25f
            )
            GLES20.glUniform1f(alphaLoc, (.91f - tuning.transparency * .17f).coerceIn(.72f, .96f))

            val buffer = vertices ?: return
            val stride = 7 * 4
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, stride, buffer)
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(normalLoc)
            GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, stride, buffer)
            buffer.position(6)
            GLES20.glEnableVertexAttribArray(regionLoc)
            GLES20.glVertexAttribPointer(regionLoc, 1, GLES20.GL_FLOAT, false, stride, buffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(normalLoc)
            GLES20.glDisableVertexAttribArray(regionLoc)
        }

        private fun createProgram() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, """
                uniform mat4 uMvp;
                uniform mat4 uModel;
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                attribute float aRegion;
                varying vec3 vNormal;
                varying vec3 vWorld;
                varying vec3 vObject;
                varying float vRegion;
                void main() {
                    vec4 world = uModel * vec4(aPosition, 1.0);
                    vWorld = world.xyz;
                    vObject = aPosition;
                    vNormal = normalize(mat3(uModel) * aNormal);
                    vRegion = aRegion;
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
                varying float vRegion;

                void main() {
                    vec3 N = normalize(vNormal);
                    vec3 L1 = normalize(uLight);
                    vec3 L2 = normalize(vec3(-uLight.x * .78, uLight.y * .35, 1.15));
                    vec3 V = normalize(vec3(0.0, -2.72, 4.30) - vWorld);
                    vec3 H1 = normalize(L1 + V);
                    vec3 H2 = normalize(L2 + V);

                    float ndl1 = max(dot(N, L1), 0.0);
                    float ndl2 = max(dot(N, L2), 0.0);
                    float ndv = max(dot(N, V), 0.0);
                    float fresnel = pow(1.0 - ndv, 4.1);

                    float specNeedle1 = pow(max(dot(N, H1), 0.0), 170.0);
                    float specNeedle2 = pow(max(dot(N, H2), 0.0), 130.0);
                    float specWide1 = pow(max(dot(N, H1), 0.0), 24.0);
                    float specWide2 = pow(max(dot(N, H2), 0.0), 16.0);

                    // vRegion : 0 table, 1 couronne, 2 biseau/ceinture, 3 pavillon.
                    float side = smoothstep(1.35, 2.55, vRegion);
                    float crown = 1.0 - abs(vRegion - 1.0);
                    crown = clamp(crown, 0.0, 1.0);

                    // Corps sombre/translucide pour laisser les reflets dominer, comme sur un diamant poli.
                    float depthTone = clamp(.66 + vObject.z * .30, .38, .92);
                    vec3 body = uBaseColor * (.16 + ndl1 * .30 + ndl2 * .15) * depthTone;

                    // Reflets très blancs et très durs sur les côtés et le biseau.
                    float sideFlash = side * (
                        specNeedle1 * 4.2 +
                        specNeedle2 * 3.1 +
                        specWide1 * 1.25 +
                        fresnel * 1.45
                    );
                    vec3 whiteFlash = vec3(1.0, .995, .98) * sideFlash;

                    // Couronne : éclats moins continus, mais plus nombreux.
                    vec3 crownFlash = vec3(.83,.94,1.0) * crown *
                        (specNeedle1 * 2.3 + specNeedle2 * 1.6 + specWide2 * .48);

                    // Réflexion froide générale.
                    vec3 rim = vec3(.34,.72,1.0) * fresnel * (1.0 + side * .9);

                    // Deux bandes spéculaires qui donnent le côté "miroir" des faces polies.
                    float bandA = pow(max(0.0, 1.0 - abs(dot(N, normalize(vec3(.78,-.18,.60))))), 18.0);
                    float bandB = pow(max(0.0, 1.0 - abs(dot(N, normalize(vec3(-.62,.30,.72))))), 22.0);
                    vec3 polishedBands = vec3(1.0) * side * (bandA * .78 + bandB * .62);

                    // Feu du diamant : dispersion légère localisée sur les zones déjà lumineuses.
                    float phase = vObject.x * 8.2 + vObject.y * 11.7 + vObject.z * 4.0;
                    vec3 spectral = vec3(
                        .95 + .05 * sin(phase),
                        .72 + .28 * sin(phase + 2.094),
                        .90 + .10 * sin(phase + 4.188)
                    );
                    float fireMask = clamp((specWide1 + specWide2 + fresnel) * (side * .75 + crown * .28), 0.0, 1.0);
                    vec3 fire = spectral * fireMask * .34;

                    float back = pow(max(dot(-N, L1), 0.0), 2.0);
                    vec3 internalLight = vec3(.20,.48,.92) * back * .23;

                    vec3 color = body + whiteFlash + crownFlash + rim + polishedBands + fire + internalLight;
                    // Tone mapping doux : conserve les blancs brillants sans transformer tout le bouton en bleu plat.
                    color = color / (color + vec3(.72));
                    color += vec3(1.0) * clamp(sideFlash * .16, 0.0, .22);
                    color = clamp(color, 0.0, 1.0);

                    float alpha = clamp(uAlpha + fresnel * .08 + side * .045, .72, .985);
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
            regionLoc = GLES20.glGetAttribLocation(program, "aRegion")
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
            val halfH = .92f
            val halfW = (halfH * aspect).coerceIn(.92f, 7.2f)
            val cornerX = (halfH * .54f).coerceAtMost(halfW * .22f)
            val cornerY = halfH * .32f

            val facet = tuning.facetDepth.coerceIn(0f, 1f)
            val bevel = tuning.bevel.coerceIn(0f, 1f)
            val girdleZ = -.015f
            val crownZ = .20f + bevel * .10f
            val tableZ = crownZ + .16f + facet * .075f
            val lowerZ = -.31f - facet * .16f

            val outer = octagon(halfW, halfH, cornerX, cornerY, girdleZ)
            val crown = octagon(
                halfW - halfH * (.14f + bevel * .05f),
                halfH * (.72f - bevel * .035f),
                cornerX * .80f,
                cornerY * .78f,
                crownZ
            )
            val table = octagon(
                halfW - halfH * (.36f + facet * .035f),
                halfH * (.49f - facet * .025f),
                cornerX * .55f,
                cornerY * .52f,
                tableZ
            )
            val lower = Array(8) { i ->
                floatArrayOf(outer[i][0] * .915f, outer[i][1] * .89f, lowerZ)
            }

            val data = ArrayList<Float>(8 * 6 * 7 * 4)

            fun addTri(a: FloatArray, b: FloatArray, c: FloatArray, region: Float) {
                val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
                val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.00001f)
                nx /= len; ny /= len; nz /= len
                arrayOf(a, b, c).forEach { v ->
                    data.add(v[0]); data.add(v[1]); data.add(v[2])
                    data.add(nx); data.add(ny); data.add(nz)
                    data.add(region)
                }
            }

            fun connectRings(a: Array<FloatArray>, b: Array<FloatArray>, region: Float) {
                for (i in 0 until 8) {
                    val n = (i + 1) % 8
                    addTri(a[i], a[n], b[n], region)
                    addTri(a[i], b[n], b[i], region)
                }
            }

            // Table parfaitement plane.
            val tableCenter = floatArrayOf(0f, 0f, tableZ)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                addTri(table[i], table[n], tableCenter, 0f)
            }

            // Couronne inclinée.
            connectRings(table, crown, 1f)

            // Biseau/ceinture : zone volontairement très réfléchissante.
            connectRings(crown, outer, 2f)

            // Pavillon inférieur.
            connectRings(outer, lower, 3f)
            val bottomCenter = floatArrayOf(0f, 0f, lowerZ - .055f)
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                addTri(lower[n], lower[i], bottomCenter, 3f)
            }

            vertexCount = data.size / 7
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
        value.backgroundTintList = null
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
    private const val WRAPPED_TAG = "hp_true_3d_wrapped_v3"
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
