package com.amaury.pointage

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class DiamondQuality(val fps: Int, val facetSegments: Int, val effects: Float, val sensorDelay: Int) {
    ECO(24, 16, .42f, SensorManager.SENSOR_DELAY_UI),
    BALANCED(36, 16, .68f, SensorManager.SENSOR_DELAY_GAME),
    HIGH(50, 16, .86f, SensorManager.SENSOR_DELAY_GAME),
    ULTRA(60, 16, 1f, SensorManager.SENSOR_DELAY_GAME)
}

object DiamondDeviceProfile {
    private val cache = WeakHashMap<Context, DiamondQuality>()

    fun quality(context: Context): DiamondQuality {
        val app = context.applicationContext
        return cache[app] ?: detect(app).also { cache[app] = it }
    }

    private fun detect(context: Context): DiamondQuality {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        val low = am.isLowRamDevice
        val sdk = Build.VERSION.SDK_INT
        return when {
            low || ramGb < 3.0 || cores <= 4 -> DiamondQuality.ECO
            ramGb < 5.0 || cores <= 6 || sdk < 29 -> DiamondQuality.BALANCED
            ramGb < 8.0 || cores <= 7 -> DiamondQuality.HIGH
            else -> DiamondQuality.ULTRA
        }
    }
}

class True3DButtonTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val quality = DiamondDeviceProfile.quality(context)
    private val renderer = CrystalMeshRenderer(quality)
    private val renderThread = HandlerThread("hp-diamond-3d-${quality.name.lowercase()}").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private var egl: EglSession? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var lastFrameMs = 0L
    private val minFrameMs = 1000L / quality.fps.coerceAtLeast(1)
    @Volatile private var renderQueued = false

    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
    }

    fun setLightAngle(angle: Float) {
        renderer.baseLightAngle = angle
        requestRender()
    }

    fun setPressedDepth(pressed: Boolean) {
        renderer.pressed = pressed
        requestRender(true)
    }

    fun setCrystalTuning(value: DiamondTuning) {
        renderer.tuning = value
        requestRender(true)
    }

    fun setBaseColor(color: Int) {
        renderer.baseColor = color
        requestRender(true)
    }

    fun setDevicePose(pitch: Float, roll: Float, yaw: Float) {
        renderer.targetPitch = pitch.coerceIn(-38f, 38f)
        renderer.targetRoll = roll.coerceIn(-38f, 38f)
        renderer.targetYaw = yaw
        requestRender()
    }

    private fun requestRender(force: Boolean = false) {
        if (renderQueued && !force) return
        renderQueued = true
        renderHandler.post {
            val now = SystemClock.uptimeMillis()
            val wait = if (force) 0L else (minFrameMs - (now - lastFrameMs)).coerceAtLeast(0L)
            if (wait > 0) {
                renderHandler.postDelayed({
                    renderQueued = false
                    drawFrame()
                }, wait)
            } else {
                renderQueued = false
                drawFrame()
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        renderHandler.post {
            releaseEgl()
            egl = EglSession(surface, quality)
            drawFrame()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        requestRender(true)
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
        lastFrameMs = SystemClock.uptimeMillis()
    }

    private fun releaseEgl() {
        egl?.release()
        egl = null
    }

    private class EglSession(texture: SurfaceTexture, quality: DiamondQuality) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val depth = if (quality == DiamondQuality.ECO) 16 else 24
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, depth,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            surface = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                texture,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /**
     * Géométrie construite uniquement depuis le squelette visuel validé :
     * table centrale polygonale, deux couronnes étoilées, ceinture fine et
     * pavillon convergeant vers la pointe. Les triangles OpenGL ne sont pas
     * assimilés aux facettes visuelles : un plan peut être composé de deux
     * triangles qui partagent le même identifiant et la même normale.
     */
    private class CrystalMeshRenderer(private val quality: DiamondQuality) {
        var baseLightAngle = -55f
        var targetPitch = 0f
        var targetRoll = 0f
        var targetYaw = 0f
        var pressed = false
        var tuning = DiamondTuning()
        var baseColor = Color.rgb(40, 120, 210)

        private var smoothPitch = 0f
        private var smoothRoll = 0f
        private var smoothYaw = 0f
        private var program = 0

        private var pLoc = 0
        private var nLoc = 0
        private var rLoc = 0
        private var mvpLoc = 0
        private var modelLoc = 0
        private var lightLoc = 0
        private var colorLoc = 0
        private var alphaLoc = 0
        private var effectsLoc = 0
        private var colorRichnessLoc = 0

        private var vertices: FloatBuffer? = null
        private var count = 0
        private var meshW = -1
        private var meshH = -1
        private var meshFacet = -1f
        private var meshBevel = -1f

        fun draw(width: Int, height: Int) {
            if (program == 0) createProgram()

            smoothPitch += (targetPitch - smoothPitch) * .20f
            smoothRoll += (targetRoll - smoothRoll) * .20f
            smoothYaw += shortestDelta(smoothYaw, targetYaw) * .14f

            if (
                meshW != width ||
                meshH != height ||
                kotlin.math.abs(meshFacet - tuning.facetDepth) > .01f ||
                kotlin.math.abs(meshBevel - tuning.bevel) > .01f
            ) {
                buildMesh(width, height)
                meshW = width
                meshH = height
                meshFacet = tuning.facetDepth
                meshBevel = tuning.bevel
            }

            GLES20.glViewport(0, 0, width, height)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val aspect = width.toFloat() / height.coerceAtLeast(1)
            val proj = FloatArray(16)
            val view = FloatArray(16)
            val model = FloatArray(16)
            val vp = FloatArray(16)
            val mvp = FloatArray(16)

            Matrix.perspectiveM(proj, 0, 24f, aspect, .1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, -2.72f, 4.3f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(
                model,
                0,
                0f,
                if (pressed) .035f else 0f,
                if (pressed) -.16f else .18f
            )
            Matrix.rotateM(model, 0, -8.2f + smoothPitch * .20f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, 2.4f - smoothRoll * .24f, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, smoothYaw * .025f, 0f, 0f, 1f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            val a = normalize(baseLightAngle + smoothYaw * .42f + smoothRoll * .55f - smoothPitch * .22f)
            val rad = Math.toRadians(a.toDouble())
            val lx = cos(rad).toFloat()
            val ly = sin(rad).toFloat()

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(lightLoc, lx * 1.55f, -ly * 1.55f, 2.15f)
            GLES20.glUniform3f(
                colorLoc,
                Color.red(baseColor) / 255f,
                Color.green(baseColor) / 255f,
                Color.blue(baseColor) / 255f
            )
            GLES20.glUniform1f(alphaLoc, (.90f - tuning.transparency * .22f).coerceIn(.62f, .94f))
            GLES20.glUniform1f(effectsLoc, quality.effects)
            GLES20.glUniform1f(
                colorRichnessLoc,
                (.45f + tuning.iceBlue * .18f + tuning.refraction * .30f).coerceIn(.35f, .92f)
            )

            val buffer = vertices ?: return
            val stride = 7 * 4

            buffer.position(0)
            GLES20.glEnableVertexAttribArray(pLoc)
            GLES20.glVertexAttribPointer(pLoc, 3, GLES20.GL_FLOAT, false, stride, buffer)

            buffer.position(3)
            GLES20.glEnableVertexAttribArray(nLoc)
            GLES20.glVertexAttribPointer(nLoc, 3, GLES20.GL_FLOAT, false, stride, buffer)

            buffer.position(6)
            GLES20.glEnableVertexAttribArray(rLoc)
            GLES20.glVertexAttribPointer(rLoc, 1, GLES20.GL_FLOAT, false, stride, buffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

            GLES20.glDisableVertexAttribArray(pLoc)
            GLES20.glDisableVertexAttribArray(nLoc)
            GLES20.glDisableVertexAttribArray(rLoc)
        }

        private fun createProgram() {
            val vertexShader = compile(
                GLES20.GL_VERTEX_SHADER,
                """
                uniform mat4 uMvp;
                uniform mat4 uModel;
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                attribute float aRegion;
                varying vec3 vN;
                varying vec3 vW;
                varying vec3 vO;
                varying float vR;
                void main() {
                    vec4 w = uModel * vec4(aPosition, 1.0);
                    vW = w.xyz;
                    vO = aPosition;
                    vN = normalize(mat3(uModel) * aNormal);
                    vR = aRegion;
                    gl_Position = uMvp * vec4(aPosition, 1.0);
                }
                """.trimIndent()
            )

            val fragmentShader = compile(
                GLES20.GL_FRAGMENT_SHADER,
                """
                precision mediump float;
                uniform vec3 uLight;
                uniform vec3 uColor;
                uniform float uAlpha;
                uniform float uEffects;
                uniform float uColorRichness;
                varying vec3 vN;
                varying vec3 vW;
                varying vec3 vO;
                varying float vR;

                void main() {
                    vec3 N = normalize(vN);
                    vec3 L = normalize(uLight);
                    vec3 V = normalize(vec3(0.0, -2.72, 4.30) - vW);
                    vec3 H = normalize(L + V);

                    float diffuse = max(dot(N, L), 0.0);
                    float nv = max(dot(N, V), 0.0);
                    float fresnel = pow(1.0 - nv, 3.0);
                    float specular = pow(max(dot(N, H), 0.0), mix(42.0, 128.0, uEffects));

                    float facetSeed = fract(sin((vR + 1.0) * 12.9898) * 43758.5453);
                    float facetSeed2 = fract(sin((vR + 7.0) * 31.771) * 19427.173);
                    float shade = mix(.64, 1.28, facetSeed);
                    float chroma = (facetSeed2 - .5) * uColorRichness;

                    vec3 warm = vec3(
                        uColor.r * (1.0 + chroma * .20),
                        uColor.g * (1.0 - chroma * .10),
                        uColor.b * (1.0 + chroma * .14)
                    );
                    vec3 deep = mix(warm * .42, warm, .34 + diffuse * .66);
                    vec3 body = deep * shade * (.74 + vO.z * .18);

                    float flash = (specular * 4.8 + fresnel * 1.55) * mix(.58, 1.0, uEffects);
                    vec3 fire = vec3(
                        1.0 + chroma * .16,
                        .90 - chroma * .10,
                        .96 + chroma * .18
                    ) * flash * .16 * uEffects;
                    vec3 rim = mix(warm, vec3(1.0), .70) * fresnel * (.42 + uEffects * .58);
                    vec3 color = body + vec3(1.0) * flash + rim + fire;
                    color = color / (color + vec3(.62));

                    float alpha = clamp(uAlpha + fresnel * .08 + specular * .04, .58, .98);
                    gl_FragColor = vec4(clamp(color, 0.0, 1.0), alpha);
                }
                """.trimIndent()
            )

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }

            pLoc = GLES20.glGetAttribLocation(program, "aPosition")
            nLoc = GLES20.glGetAttribLocation(program, "aNormal")
            rLoc = GLES20.glGetAttribLocation(program, "aRegion")
            mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            modelLoc = GLES20.glGetUniformLocation(program, "uModel")
            lightLoc = GLES20.glGetUniformLocation(program, "uLight")
            colorLoc = GLES20.glGetUniformLocation(program, "uColor")
            alphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
            effectsLoc = GLES20.glGetUniformLocation(program, "uEffects")
            colorRichnessLoc = GLES20.glGetUniformLocation(program, "uColorRichness")
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
            val aspect = width.toFloat() / height.coerceAtLeast(1)
            val hh = .92f
            val hw = (hh * aspect).coerceIn(.92f, 7.2f)
            val depth = tuning.facetDepth.coerceIn(0f, 1f)
            val bevel = tuning.bevel.coerceIn(0f, 1f)

            val tableZ = .43f + bevel * .06f + depth * .04f
            val innerCrownZ = .29f + bevel * .035f
            val outerCrownZ = .17f + bevel * .02f
            val girdleTopZ = 0f
            val girdleBottomZ = -.055f - bevel * .025f
            val pavilionBreakZ = -.43f - depth * .16f
            val culetZ = -.78f - depth * .30f

            // Squelette vu du dessus : octogone central + deux anneaux étoilés + 16 points de ceinture.
            val table = ringPoints(8, hw * .48f, hh * .45f, tableZ, Math.PI / 8.0)
            val innerCrown = ringPoints(8, hw * .63f, hh * .60f, innerCrownZ, 0.0)
            val outerCrown = ringPoints(8, hw * .76f, hh * .73f, outerCrownZ, Math.PI / 8.0)
            val girdleTop = ringPoints(16, hw, hh, girdleTopZ, 0.0)
            val girdleBottom = ringPoints(16, hw * .988f, hh * .988f, girdleBottomZ, 0.0)

            // Squelette du pavillon : 8 points de rupture qui convergent vers une pointe unique.
            val pavilionBreak = ringPoints(
                8,
                hw * (.53f - depth * .03f),
                hh * (.50f - depth * .025f),
                pavilionBreakZ,
                Math.PI / 8.0
            )
            val culet = floatArrayOf(0f, 0f, culetZ)

            val data = ArrayList<Float>(1400)
            var nextFacetId = 0f

            fun normal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
                val ux = b[0] - a[0]
                val uy = b[1] - a[1]
                val uz = b[2] - a[2]
                val vx = c[0] - a[0]
                val vy = c[1] - a[1]
                val vz = c[2] - a[2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.00001f)
                nx /= len
                ny /= len
                nz /= len
                return floatArrayOf(nx, ny, nz)
            }

            fun emitVertex(v: FloatArray, n: FloatArray, facetId: Float) {
                data.add(v[0]); data.add(v[1]); data.add(v[2])
                data.add(n[0]); data.add(n[1]); data.add(n[2])
                data.add(facetId)
            }

            fun emitTriangle(
                a: FloatArray,
                b: FloatArray,
                c: FloatArray,
                facetId: Float,
                forcedNormal: FloatArray? = null
            ) {
                val n = forcedNormal ?: normal(a, b, c)
                emitVertex(a, n, facetId)
                emitVertex(b, n, facetId)
                emitVertex(c, n, facetId)
            }

            fun facetTriangle(a: FloatArray, b: FloatArray, c: FloatArray) {
                emitTriangle(a, b, c, nextFacetId)
                nextFacetId += 1f
            }

            fun facetQuad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
                val n = normal(a, b, c)
                emitTriangle(a, b, c, nextFacetId, n)
                emitTriangle(a, c, d, nextFacetId, n)
                nextFacetId += 1f
            }

            // Table : un seul plan visuel. Les 8 triangles OpenGL partagent le même ID et la même normale.
            val tableCenter = floatArrayOf(0f, 0f, tableZ)
            val tableNormal = floatArrayOf(0f, 0f, 1f)
            val tableFacetId = nextFacetId++
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                emitTriangle(table[i], table[n], tableCenter, tableFacetId, tableNormal)
            }

            // Première étoile du squelette : 8 grands plans entre table et anneau intérieur.
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                facetQuad(table[i], table[n], innerCrown[n], innerCrown[i])
            }

            // Deuxième étoile : 8 plans qui prolongent les branches vers l'extérieur.
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                facetQuad(innerCrown[i], innerCrown[n], outerCrown[n], outerCrown[i])
            }

            // Bord supérieur : 16 facettes visibles jusqu'à la ceinture, exactement selon les 16 rayons du squelette.
            for (i in 0 until 8) {
                val g0 = (2 * i) % 16
                val g1 = (2 * i + 1) % 16
                val g2 = (2 * i + 2) % 16
                facetTriangle(outerCrown[i], girdleTop[g0], girdleTop[g1])
                facetTriangle(outerCrown[i], girdleTop[g1], girdleTop[g2])
            }

            // Ceinture technique : visible en profil mais non comptée comme facette du squelette.
            for (i in 0 until 16) {
                val n = (i + 1) % 16
                val technicalId = 1000f + i
                val faceNormal = normal(girdleTop[i], girdleTop[n], girdleBottom[n])
                emitTriangle(girdleTop[i], girdleTop[n], girdleBottom[n], technicalId, faceNormal)
                emitTriangle(girdleTop[i], girdleBottom[n], girdleBottom[i], technicalId, faceNormal)
            }

            // Pavillon : 16 facettes depuis la ceinture vers la rupture intérieure.
            for (i in 0 until 8) {
                val g0 = (2 * i) % 16
                val g1 = (2 * i + 1) % 16
                val g2 = (2 * i + 2) % 16
                facetTriangle(girdleBottom[g0], girdleBottom[g1], pavilionBreak[i])
                facetTriangle(girdleBottom[g1], girdleBottom[g2], pavilionBreak[i])
            }

            // 8 grands plans finaux du pavillon vers la pointe.
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                facetTriangle(pavilionBreak[i], pavilionBreak[n], culet)
            }

            count = data.size / 7

            // Le squelette possède 57 facettes visuelles. Les triangles techniques de la ceinture sont exclus.
            check(nextFacetId.toInt() == 57) {
                "Visual diamond skeleton must expose exactly 57 facet identities, got ${nextFacetId.toInt()}"
            }

            vertices = ByteBuffer
                .allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    data.forEach { put(it) }
                    position(0)
                }
        }

        private fun ringPoints(
            count: Int,
            width: Float,
            height: Float,
            z: Float,
            angularOffset: Double
        ) = Array(count) { i ->
            val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0 + angularOffset
            floatArrayOf(
                (cos(angle) * width).toFloat(),
                (sin(angle) * height).toFloat(),
                z
            )
        }

        private fun normalize(v: Float) = ((v % 360f) + 360f) % 360f
        private fun shortestDelta(a: Float, b: Float) = ((b - a + 540f) % 360f) - 180f
    }
}

private object DiamondMotionHub : SensorEventListener {
    private val listeners = WeakHashMap<True3DButtonHost, Unit>()
    private var manager: SensorManager? = null
    private var sensor: Sensor? = null
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    fun attach(host: True3DButtonHost) {
        listeners[host] = Unit
        if (manager != null) return
        val sm = host.context.applicationContext
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager = sm
        sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let {
            sm.registerListener(
                this,
                it,
                DiamondDeviceProfile.quality(host.context).sensorDelay
            )
        }
    }

    fun detach(host: True3DButtonHost) {
        listeners.remove(host)
        if (listeners.isEmpty()) {
            manager?.unregisterListener(this)
            manager = null
            sensor = null
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        var pitch = 0f
        var roll = 0f
        var yaw = 0f

        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            yaw = Math.toDegrees(ori[0].toDouble()).toFloat()
            pitch = Math.toDegrees(ori[1].toDouble()).toFloat()
            roll = Math.toDegrees(ori[2].toDouble()).toFloat()
        } else {
            val k = .15f
            accelX += (e.values[0] - accelX) * k
            accelY += (e.values[1] - accelY) * k
            accelZ += (e.values[2] - accelZ) * k
            pitch = Math.toDegrees(
                atan2(-accelY, sqrt(accelX * accelX + accelZ * accelZ).toDouble())
            ).toFloat()
            roll = Math.toDegrees(atan2(accelX, accelZ.toDouble())).toFloat()
        }

        listeners.keys.toList().forEach { it.onDevicePose(pitch, roll, yaw) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
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
    fun setBaseColor(color: Int) = surface.setBaseColor(color)
    fun onDevicePose(pitch: Float, roll: Float, yaw: Float) =
        surface.setDevicePose(pitch, roll, yaw)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DiamondMotionHub.attach(this)
    }

    override fun onDetachedFromWindow() {
        DiamondMotionHub.detach(this)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> surface.setPressedDepth(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> surface.setPressedDepth(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}

object True3DButtonInstaller {
    private const val TAG = "hp_true_3d_wrapped_v8_visual_skeleton_57"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()

    fun install(root: View, lightAngle: Float) {
        val list = ArrayList<Button>()
        collect(root, list)
        list.forEach { wrap(it, lightAngle) }
    }

    fun updateLight(root: View, lightAngle: Float) {
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) host.setLightAngle(lightAngle)
        }
    }

    private fun collect(v: View, out: MutableList<Button>) {
        if (
            v is Button &&
            v.getTag(R.id.true3d_internal_tag) != TAG &&
            !isPrimaryPointageButton(v)
        ) {
            out.add(v)
        }
        if (v is ViewGroup && v !is True3DButtonHost) {
            for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        }
    }

    private fun isPrimaryPointageButton(button: Button): Boolean {
        val name = runCatching {
            button.resources.getResourceEntryName(button.id)
        }.getOrNull().orEmpty()
        return name == "entryButton" || name == "pauseButton" || name == "exitButton"
    }

    private fun wrap(button: Button, lightAngle: Float) {
        if (hosts.containsKey(button)) return
        val parent = button.parent as? ViewGroup ?: return
        if (parent is True3DButtonHost) return

        val index = parent.indexOfChild(button)
        val lp = button.layoutParams
        parent.removeViewAt(index)

        val host = True3DButtonHost(button.context)
        host.layoutParams = lp
        host.setTag(R.id.true3d_internal_tag, TAG)
        parent.addView(host, index)
        host.attachButton(button, DiamondTuningStore.load(button.context), lightAngle)
        button.setTag(R.id.true3d_internal_tag, TAG)
        hosts[button] = host
    }
}
