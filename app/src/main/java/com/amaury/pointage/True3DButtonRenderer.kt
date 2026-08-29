package com.amaury.pointage

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
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

enum class DiamondQuality(val fps: Int, val effects: Float, val sensorDelay: Int) {
    ECO(24, .42f, SensorManager.SENSOR_DELAY_UI),
    BALANCED(36, .68f, SensorManager.SENSOR_DELAY_GAME),
    HIGH(50, .86f, SensorManager.SENSOR_DELAY_GAME),
    ULTRA(60, 1f, SensorManager.SENSOR_DELAY_GAME)
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
        return when {
            am.isLowRamDevice || ramGb < 3.0 || cores <= 4 -> DiamondQuality.ECO
            ramGb < 5.0 || cores <= 6 || Build.VERSION.SDK_INT < 29 -> DiamondQuality.BALANCED
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
    private val renderHandler = DiamondRenderThread.handler
    private var egl: EglSession? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var lastFrameMs = 0L
    private val minFrameMs = 1000L / quality.fps.coerceAtLeast(1)
    @Volatile private var renderQueued = false
    @Volatile private var surfaceGeneration = 0L

    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
    }

    fun setLightAngle(angle: Float) { renderer.baseLightAngle = angle; requestRender() }
    fun setPressedDepth(pressed: Boolean) { renderer.pressed = pressed; requestRender(true) }
    fun setCrystalTuning(value: DiamondTuning) { renderer.tuning = value; requestRender(true) }
    fun setBaseColor(color: Int) { renderer.baseColor = color; requestRender(true) }
    fun setDevicePose(pitch: Float, roll: Float, yaw: Float) {
        renderer.targetPitch = pitch.coerceIn(-38f, 38f)
        renderer.targetRoll = roll.coerceIn(-38f, 38f)
        renderer.targetYaw = yaw
        requestRender()
    }

    private fun requestRender(force: Boolean = false) {
        if (renderQueued && !force) return
        val generation = surfaceGeneration
        renderQueued = true
        renderHandler.post {
            if (generation != surfaceGeneration) {
                renderQueued = false
                return@post
            }
            val now = SystemClock.uptimeMillis()
            val wait = if (force) 0L else (minFrameMs - (now - lastFrameMs)).coerceAtLeast(0L)
            if (wait > 0L) {
                renderHandler.postDelayed({
                    renderQueued = false
                    drawFrame(generation)
                }, wait)
            } else {
                renderQueued = false
                drawFrame(generation)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val generation = ++surfaceGeneration
        renderQueued = false
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        renderHandler.post {
            if (generation != surfaceGeneration) return@post
            releaseEgl()
            egl = EglSession(surface, quality)
            drawFrame(generation)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        requestRender(true)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceGeneration++
        renderQueued = false
        renderHandler.post {
            try {
                releaseEgl()
            } finally {
                surface.release()
            }
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        surfaceGeneration++
        renderQueued = false
        renderHandler.post { releaseEgl() }
    }

    private fun drawFrame(generation: Long) {
        if (generation != surfaceGeneration) return
        val session = egl ?: return
        session.makeCurrent()
        renderer.draw(surfaceWidth, surfaceHeight)
        session.swap()
        lastFrameMs = SystemClock.uptimeMillis()
    }

    private fun releaseEgl() {
        val session = egl ?: return
        session.makeCurrent()
        renderer.releaseGpu()
        session.release()
        egl = null
    }

    private class EglSession(texture: SurfaceTexture, quality: DiamondQuality) {
        private val surface: EGLSurface = OpenGlButtonEgl.createSurface(
            texture,
            if (quality == DiamondQuality.ECO) 16 else 24
        )

        fun makeCurrent() = OpenGlButtonEgl.makeCurrent(surface)
        fun swap() = OpenGlButtonEgl.swap(surface)
        fun release() = OpenGlButtonEgl.destroySurface(surface)
    }

    private class CrystalMeshRenderer(private val quality: DiamondQuality) {
        var baseLightAngle = -55f
        var targetPitch = 0f
        var targetRoll = 0f
        var targetYaw = 0f
        var pressed = false
        var tuning = DiamondTuning()
        var baseColor = Color.rgb(40, 120, 210)

        private val facetMemory = DiamondFacetMemory()
        private var facetTexture = 0
        private var facetTextureWidth = 0
        private var internalReturnTexture = 0
        private var internalReturnTextureWidth = 0
        private var stateUploadBuffer: ByteBuffer? = null
        private var internalUploadBuffer: ByteBuffer? = null
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val viewProjectionMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
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
        private var facetTextureLoc = 0
        private var internalReturnTextureLoc = 0
        private var facetCountLoc = 0

        private var edgeProgram = 0
        private var edgePositionLoc = 0
        private var edgeMvpLoc = 0
        private var edgeColorLoc = 0

        private var vertices: FloatBuffer? = null
        private var count = 0
        private var pavilionStartVertex = 0
        private var pavilionVertexCount = 0
        private var edgeVertices: FloatBuffer? = null
        private var edgeVertexCount = 0
        private var meshW = -1
        private var meshH = -1
        private var meshFacet = -1f
        private var meshBevel = -1f

        fun draw(width: Int, height: Int) {
            if (program == 0) createProgram()
            if (edgeProgram == 0) createEdgeProgram()

            smoothPitch += (targetPitch - smoothPitch) * .20f
            smoothRoll += (targetRoll - smoothRoll) * .20f
            smoothYaw += shortestDelta(smoothYaw, targetYaw) * .14f

            if (
                meshW != width || meshH != height ||
                kotlin.math.abs(meshFacet - tuning.facetDepth) > .01f ||
                kotlin.math.abs(meshBevel - tuning.bevel) > .01f
            ) {
                buildMesh(width, height)
                meshW = width
                meshH = height
                meshFacet = tuning.facetDepth
                meshBevel = tuning.bevel
            }

            ensureFacetTextures()
            facetMemory.update(
                baseLightAngle,
                smoothPitch,
                smoothRoll,
                smoothYaw,
                tuning.refraction,
                tuning.sparkle
            )
            uploadFacetTextures()

            GLES20.glViewport(0, 0, width, height)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val aspect = width.toFloat() / height.coerceAtLeast(1)
            Matrix.perspectiveM(projectionMatrix, 0, 24f, aspect, .1f, 20f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, -2.72f, 4.3f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, if (pressed) .035f else 0f, if (pressed) -.16f else .18f)
            Matrix.rotateM(modelMatrix, 0, -8.2f + smoothPitch * .20f, 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, 2.4f - smoothRoll * .24f, 0f, 1f, 0f)
            Matrix.rotateM(modelMatrix, 0, smoothYaw * .025f, 0f, 0f, 1f)
            Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)

            val a = normalize(baseLightAngle)
            val rad = Math.toRadians(a.toDouble())
            drawFacets(mvpMatrix, modelMatrix, cos(rad).toFloat(), sin(rad).toFloat())
            drawTrueEdges(mvpMatrix)
        }

        fun releaseGpu() {
            if (facetTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(facetTexture), 0)
                facetTexture = 0
                facetTextureWidth = 0
            }
            if (internalReturnTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(internalReturnTexture), 0)
                internalReturnTexture = 0
                internalReturnTextureWidth = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (edgeProgram != 0) {
                GLES20.glDeleteProgram(edgeProgram)
                edgeProgram = 0
            }
        }

        private fun ensureFacetTextures() {
            val width = facetMemory.size().coerceAtLeast(1)
            if (facetTexture == 0 || facetTextureWidth != width) {
                if (facetTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(facetTexture), 0)
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                facetTexture = ids[0]
                facetTextureWidth = width
                configureFacetTexture(facetTexture, width)
            }
            if (internalReturnTexture == 0 || internalReturnTextureWidth != width) {
                if (internalReturnTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(internalReturnTexture), 0)
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                internalReturnTexture = ids[0]
                internalReturnTextureWidth = width
                configureFacetTexture(internalReturnTexture, width)
            }
        }

        private fun configureFacetTexture(texture: Int, width: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(width * 4)
            )
        }

        private fun uploadFacetTextures() {
            val stateBytes = facetMemory.toRgbaBytes()
            val internalBytes = facetMemory.toInternalReturnRgbaBytes()
            if (stateBytes.isEmpty() || internalBytes.isEmpty()) return

            val stateBuffer = reusableUploadBuffer(stateUploadBuffer, stateBytes.size).also { stateUploadBuffer = it }
            stateBuffer.clear()
            stateBuffer.put(stateBytes)
            stateBuffer.flip()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, facetTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, facetMemory.size(), 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, stateBuffer
            )

            val internalBuffer = reusableUploadBuffer(internalUploadBuffer, internalBytes.size).also { internalUploadBuffer = it }
            internalBuffer.clear()
            internalBuffer.put(internalBytes)
            internalBuffer.flip()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalReturnTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, facetMemory.size(), 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, internalBuffer
            )
        }

        private fun reusableUploadBuffer(current: ByteBuffer?, requiredSize: Int): ByteBuffer {
            if (current != null && current.capacity() >= requiredSize) return current
            return ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder())
        }

        private fun drawFacets(mvp: FloatArray, model: FloatArray, lx: Float, ly: Float) {
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
            GLES20.glUniform1f(alphaLoc, (.88f - tuning.transparency * .30f).coerceIn(.52f, .88f))
            GLES20.glUniform1f(effectsLoc, quality.effects)
            GLES20.glUniform1f(
                colorRichnessLoc,
                (.45f + tuning.iceBlue * .18f + tuning.refraction * .30f).coerceIn(.35f, .92f)
            )
            GLES20.glUniform1f(facetCountLoc, facetMemory.size().coerceAtLeast(1).toFloat())

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, facetTexture)
            GLES20.glUniform1i(facetTextureLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalReturnTexture)
            GLES20.glUniform1i(internalReturnTextureLoc, 1)

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

            if (pavilionVertexCount > 0) {
                GLES20.glDepthMask(true)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, pavilionStartVertex, pavilionVertexCount)
            }

            GLES20.glDepthMask(false)
            if (pavilionStartVertex > 0) {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, pavilionStartVertex)
            }
            GLES20.glDepthMask(true)

            GLES20.glDisableVertexAttribArray(pLoc)
            GLES20.glDisableVertexAttribArray(nLoc)
            GLES20.glDisableVertexAttribArray(rLoc)
        }

        private fun drawTrueEdges(mvp: FloatArray) {
            val buffer = edgeVertices ?: return
            if (edgeVertexCount <= 0) return
            GLES20.glUseProgram(edgeProgram)
            GLES20.glUniformMatrix4fv(edgeMvpLoc, 1, false, mvp, 0)
            val r = Color.red(baseColor) / 255f
            val g = Color.green(baseColor) / 255f
            val b = Color.blue(baseColor) / 255f
            GLES20.glUniform4f(edgeColorLoc, r * .42f + .58f, g * .42f + .58f, b * .42f + .58f, .46f)
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(edgePositionLoc)
            GLES20.glVertexAttribPointer(edgePositionLoc, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glDepthMask(false)
            GLES20.glLineWidth(1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, edgeVertexCount)
            GLES20.glDepthMask(true)
            GLES20.glDisableVertexAttribArray(edgePositionLoc)
        }

        private fun createProgram() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, """
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
            """.trimIndent())

            val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform vec3 uLight;
                uniform vec3 uColor;
                uniform float uAlpha;
                uniform float uEffects;
                uniform float uColorRichness;
                uniform float uFacetCount;
                uniform sampler2D uFacetState;
                uniform sampler2D uInternalReturnState;
                varying vec3 vN;
                varying vec3 vW;
                varying vec3 vO;
                varying float vR;

                void main() {
                    float facetIndex = floor(vR + 0.5);
                    float tx = (facetIndex + 0.5) / max(uFacetCount, 1.0);
                    vec4 state = texture2D(uFacetState, vec2(tx, 0.5));
                    vec4 internalState = texture2D(uInternalReturnState, vec2(tx, 0.5));
                    float tone = state.r;
                    float transparencyRef = state.g;
                    float memoryBrightness = state.b;
                    float memoryReflection = state.a;
                    float escapedReturn = internalState.r;
                    float rawInternalReturn = internalState.g;
                    float exitTransmission = internalState.b;

                    vec3 N = normalize(vN);
                    vec3 L = normalize(uLight);
                    vec3 V = normalize(vec3(0.0, -2.72, 4.30) - vW);
                    vec3 H = normalize(L + V);
                    float nl = max(dot(N, L), 0.0);
                    float nv = max(dot(N, V), 0.0);
                    float viewFacing = abs(dot(N, V));
                    float nh = max(dot(N, H), 0.0);

                    float f0 = 0.1724;
                    float fresnel = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
                    float crystalEnergy = mix(.18, 1.0, memoryBrightness);
                    float diffuse = nl * crystalEnergy;

                    float broadSpecular = pow(nh, mix(24.0, 46.0, uEffects));
                    float sharpSpecular = pow(nh, mix(92.0, 220.0, uEffects));
                    float reflectionEnergy = memoryReflection * crystalEnergy;
                    float specular = broadSpecular * (.10 + reflectionEnergy * .28)
                        + sharpSpecular * (.24 + reflectionEnergy * 1.85);

                    float upperGate = smoothstep(-0.08, 0.14, vO.z);
                    float pavilionGate = 1.0 - smoothstep(-0.22, 0.04, vO.z);
                    float transmissionAngle = (1.0 - fresnel) * smoothstep(0.06, 0.96, viewFacing);
                    float opticalTransmission = upperGate * transmissionAngle * mix(.55, .94, transparencyRef);

                    float chroma = (tone - 0.5) * uColorRichness;
                    vec3 facetColor = vec3(
                        uColor.r * (1.0 + chroma * .30),
                        uColor.g * (1.0 - chroma * .13),
                        uColor.b * (1.0 + chroma * .22)
                    );

                    float facetContrast = .82 + abs(tone - .5) * .36;
                    float depthAttenuation = mix(1.0, .62, pavilionGate);
                    vec3 absorbed = facetColor * (.20 + .80 * depthAttenuation);
                    vec3 litBody = mix(absorbed * .34, facetColor, .12 + diffuse * .52 + memoryBrightness * .32);
                    vec3 body = litBody * facetContrast * mix(1.0, .76, opticalTransmission);

                    float interiorDepth = upperGate * rawInternalReturn * (.22 + exitTransmission * .58);
                    vec3 interiorColor = mix(facetColor * .52, vec3(1.0), .34)
                        * interiorDepth
                        * (.34 + uColorRichness * .32);

                    float flash = specular * mix(.55, 1.0, uEffects);
                    vec3 neutralFlash = vec3(1.0) * flash;
                    vec3 rim = mix(facetColor, vec3(1.0), .78)
                        * fresnel
                        * (.10 + reflectionEnergy * .72);

                    float dispersionGate = clamp(
                        escapedReturn * .82 + sharpSpecular * reflectionEnergy * .58,
                        0.0, 1.0
                    ) * uColorRichness;
                    float spectralPhase = fract((facetIndex + 1.0) * .6180339 + tone * .73);
                    vec3 dispersion = vec3(
                        .92 + .22 * spectralPhase,
                        .94 + .12 * (1.0 - abs(spectralPhase - .5) * 2.0),
                        .95 + .25 * (1.0 - spectralPhase)
                    ) * dispersionGate * .22;

                    vec3 returnColor = mix(facetColor, vec3(1.0), .60)
                        * upperGate
                        * escapedReturn
                        * (.62 + uColorRichness * .42);

                    vec3 color = body + interiorColor + neutralFlash + rim + dispersion + returnColor;
                    color = color / (color + vec3(.72));
                    color = pow(clamp(color, 0.0, 1.0), vec3(.92));

                    float facetAlpha = uAlpha * mix(.48, .76, transparencyRef);
                    float faceOnCrystal = opticalTransmission * (.30 + exitTransmission * .16);
                    float alpha = facetAlpha
                        - faceOnCrystal
                        - escapedReturn * upperGate * .08
                        + fresnel * .18
                        + memoryReflection * .035;
                    alpha = clamp(alpha, .18, .88);
                    gl_FragColor = vec4(clamp(color, 0.0, 1.0), alpha);
                }
            """.trimIndent())

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
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
            facetTextureLoc = GLES20.glGetUniformLocation(program, "uFacetState")
            internalReturnTextureLoc = GLES20.glGetUniformLocation(program, "uInternalReturnState")
            facetCountLoc = GLES20.glGetUniformLocation(program, "uFacetCount")
        }

        private fun createEdgeProgram() {
            val vs = compile(
                GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uMvp; attribute vec3 aPosition; void main(){gl_Position=uMvp*vec4(aPosition,1.0);}"
            )
            val fs = compile(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; uniform vec4 uEdgeColor; void main(){gl_FragColor=uEdgeColor;}"
            )
            edgeProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(edgeProgram, vs)
            GLES20.glAttachShader(edgeProgram, fs)
            GLES20.glLinkProgram(edgeProgram)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(edgeProgram, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(edgeProgram) }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            edgePositionLoc = GLES20.glGetAttribLocation(edgeProgram, "aPosition")
            edgeMvpLoc = GLES20.glGetUniformLocation(edgeProgram, "uMvp")
            edgeColorLoc = GLES20.glGetUniformLocation(edgeProgram, "uEdgeColor")
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

        private data class TopologyFace(
            val region: DiamondFacetRegion,
            val vertices: List<FloatArray>
        )

        private fun buildMesh(width: Int, height: Int) {
            val aspect = width.toFloat() / height.coerceAtLeast(1)
            val hh = .92f
            val hw = (hh * aspect).coerceIn(.92f, 7.2f)
            val depth = tuning.facetDepth.coerceIn(0f, 1f)
            val bevel = tuning.bevel.coerceIn(0f, 1f)
            val tableZ = .405f + bevel * .06f + depth * .04f
            val innerZ = .29f + bevel * .035f
            val outerZ = .17f + bevel * .02f
            val gtZ = 0f
            val gbZ = -.055f - bevel * .025f
            val pbZ = -.43f - depth * .16f
            val culetZ = -.78f - depth * .30f

            val table = ringPoints(8, hw * .43f, hh * .40f, tableZ, Math.PI / 8.0)
            val inner = ringPoints(8, hw * .63f, hh * .60f, innerZ, 0.0)
            val outer = ringPoints(8, hw * .76f, hh * .73f, outerZ, Math.PI / 8.0)
            val gt = ringPoints(16, hw, hh, gtZ, 0.0)
            val gb = ringPoints(16, hw * .988f, hh * .988f, gbZ, 0.0)
            val pb = ringPoints(8, hw * (.53f - depth * .03f), hh * (.50f - depth * .025f), pbZ, Math.PI / 8.0)
            val culet = floatArrayOf(0f, 0f, culetZ)

            val faces = ArrayList<TopologyFace>(73)
            faces += TopologyFace(DiamondFacetRegion.TABLE, table.toList())

            for (i in 0 until 8) {
                val n = (i + 1) % 8
                faces += TopologyFace(DiamondFacetRegion.CROWN_INNER, listOf(table[i], table[n], inner[n], inner[i]))
            }
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                faces += TopologyFace(DiamondFacetRegion.CROWN_MIDDLE, listOf(inner[i], inner[n], outer[n], outer[i]))
            }
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                val g0 = 2 * i
                val g1 = 2 * i + 1
                val g2 = (2 * i + 2) % 16
                faces += TopologyFace(DiamondFacetRegion.CROWN_OUTER, listOf(outer[i], gt[g0], gt[g1]))
                faces += TopologyFace(DiamondFacetRegion.CROWN_OUTER, listOf(outer[i], gt[g1], gt[g2], outer[n]))
            }
            for (i in 0 until 16) {
                val n = (i + 1) % 16
                faces += TopologyFace(DiamondFacetRegion.GIRDLE, listOf(gt[i], gt[n], gb[n], gb[i]))
            }
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                val g0 = 2 * i
                val g1 = 2 * i + 1
                val g2 = (2 * i + 2) % 16
                faces += TopologyFace(DiamondFacetRegion.PAVILION_UPPER, listOf(gb[g0], gb[g1], pb[i]))
                faces += TopologyFace(DiamondFacetRegion.PAVILION_UPPER, listOf(gb[g1], gb[g2], pb[n], pb[i]))
            }
            for (i in 0 until 8) {
                val n = (i + 1) % 8
                faces += TopologyFace(DiamondFacetRegion.PAVILION_LOWER, listOf(pb[i], pb[n], culet))
            }

            facetMemory.resetTopology(faces.size)
            val data = ArrayList<Float>(faces.size * 30)
            val edgeData = ArrayList<Float>(faces.size * 18)
            val edgeKeys = HashSet<String>()

            fun opticalNormal(points: List<FloatArray>): FloatArray {
                var nx = 0f
                var ny = 0f
                var nz = 0f
                for (i in points.indices) {
                    val a = points[i]
                    val b = points[(i + 1) % points.size]
                    nx += (a[1] - b[1]) * (a[2] + b[2])
                    ny += (a[2] - b[2]) * (a[0] + b[0])
                    nz += (a[0] - b[0]) * (a[1] + b[1])
                }
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.00001f)
                return floatArrayOf(nx / len, ny / len, nz / len)
            }
            fun emitVertex(v: FloatArray, n: FloatArray, id: Float) {
                data.add(v[0]); data.add(v[1]); data.add(v[2])
                data.add(n[0]); data.add(n[1]); data.add(n[2])
                data.add(id)
            }

            fun emitTriangle(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray, id: Float) {
                emitVertex(a, n, id)
                emitVertex(b, n, id)
                emitVertex(c, n, id)
            }

            fun vertexKey(v: FloatArray) = "${v[0].toBits()}:${v[1].toBits()}:${v[2].toBits()}"

            fun emitBoundaryEdge(a: FloatArray, b: FloatArray) {
                val ka = vertexKey(a)
                val kb = vertexKey(b)
                val key = if (ka < kb) "$ka|$kb" else "$kb|$ka"
                if (!edgeKeys.add(key)) return
                edgeData.add(a[0]); edgeData.add(a[1]); edgeData.add(a[2])
                edgeData.add(b[0]); edgeData.add(b[1]); edgeData.add(b[2])
            }

            var pavilionStarted = false
            pavilionStartVertex = 0
            faces.forEachIndexed { id, face ->
                if (!pavilionStarted && (
                        face.region == DiamondFacetRegion.PAVILION_UPPER ||
                        face.region == DiamondFacetRegion.PAVILION_LOWER
                    )) {
                    pavilionStartVertex = data.size / 7
                    pavilionStarted = true
                }

                val n = opticalNormal(face.vertices)
                facetMemory.defineFacet(id, face.region, n[0], n[1], n[2])

                for (i in 1 until face.vertices.lastIndex) {
                    emitTriangle(face.vertices[0], face.vertices[i], face.vertices[i + 1], n, id.toFloat())
                }
                for (i in face.vertices.indices) {
                    emitBoundaryEdge(face.vertices[i], face.vertices[(i + 1) % face.vertices.size])
                }
            }

            count = data.size / 7
            pavilionVertexCount = if (pavilionStarted) count - pavilionStartVertex else 0
            vertices = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { data.forEach { put(it) }; position(0) }

            edgeVertexCount = edgeData.size / 3
            edgeVertices = ByteBuffer.allocateDirect(edgeData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { edgeData.forEach { put(it) }; position(0) }
        }

        private fun ringPoints(count: Int, width: Float, height: Float, z: Float, offset: Double) =
            Array(count) { i ->
                val a = (Math.PI * 2.0 * i / count) - Math.PI / 2.0 + offset
                floatArrayOf((cos(a) * width).toFloat(), (sin(a) * height).toFloat(), z)
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
        val sm = host.context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager = sm
        sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sm.registerListener(this, it, DiamondDeviceProfile.quality(host.context).sensorDelay) }
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
            pitch = Math.toDegrees(atan2(-accelY, sqrt(accelX * accelX + accelZ * accelZ).toDouble())).toFloat()
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
    fun onDevicePose(pitch: Float, roll: Float, yaw: Float) = surface.setDevicePose(pitch, roll, yaw)

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
    private const val TAG = "hp_true_3d_wrapped_v13_optical_facets"
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
        if (v is Button && v.getTag(R.id.true3d_internal_tag) != TAG && !isPrimaryPointageButton(v)) out.add(v)
        if (v is ViewGroup && v !is True3DButtonHost) {
            for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        }
    }

    private fun isPrimaryPointageButton(button: Button): Boolean {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
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