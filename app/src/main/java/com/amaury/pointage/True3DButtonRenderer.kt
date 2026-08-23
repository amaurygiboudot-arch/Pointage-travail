package com.amaury.pointage

import android.content.Context
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vrai bouton diamant 3D OpenGL, sensible à l'inclinaison physique du téléphone. */
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

    fun setLightAngle(angle: Float) { renderer.baseLightAngle = angle; requestRender() }
    fun setPressedDepth(pressed: Boolean) { renderer.pressed = pressed; requestRender() }
    fun setCrystalTuning(value: DiamondTuning) { renderer.tuning = value; requestRender() }
    fun setDevicePose(pitch: Float, roll: Float, yaw: Float) {
        renderer.targetPitch = pitch.coerceIn(-38f, 38f)
        renderer.targetRoll = roll.coerceIn(-38f, 38f)
        renderer.targetYaw = yaw
        requestRender()
    }

    private fun requestRender() = renderHandler.post { drawFrame() }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1); surfaceHeight = height.coerceAtLeast(1)
        renderHandler.post { releaseEgl(); egl = EglSession(surface); drawFrame() }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1); surfaceHeight = height.coerceAtLeast(1); requestRender()
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { renderHandler.post { releaseEgl() }; return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderHandler.post { releaseEgl(); renderThread.quitSafely() }
    }

    private fun drawFrame() {
        val session = egl ?: return
        session.makeCurrent(); renderer.draw(surfaceWidth, surfaceHeight); session.swap()
    }
    private fun releaseEgl() { egl?.release(); egl = null }

    private class EglSession(texture: SurfaceTexture) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        init {
            val v = IntArray(2); check(EGL14.eglInitialize(display, v, 0, v, 1))
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,24,EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,n,0) && n[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
        }
        fun makeCurrent() { EGL14.eglMakeCurrent(display,surface,surface,context) }
        fun swap() { EGL14.eglSwapBuffers(display,surface) }
        fun release() {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglTerminate(display)
        }
    }

    private class CrystalMeshRenderer {
        var baseLightAngle = -55f
        var targetPitch = 0f
        var targetRoll = 0f
        var targetYaw = 0f
        var pressed = false
        var tuning = DiamondTuning()
        private var smoothPitch = 0f
        private var smoothRoll = 0f
        private var smoothYaw = 0f
        private var program = 0
        private var pLoc=0; private var nLoc=0; private var rLoc=0; private var mvpLoc=0; private var modelLoc=0; private var lightLoc=0; private var colorLoc=0; private var alphaLoc=0
        private var vertices: FloatBuffer? = null
        private var count = 0

        fun draw(width: Int, height: Int) {
            if (program == 0) createProgram()
            smoothPitch += (targetPitch - smoothPitch) * .20f
            smoothRoll += (targetRoll - smoothRoll) * .20f
            smoothYaw += shortestDelta(smoothYaw, targetYaw) * .14f
            buildMesh(width,height)
            GLES20.glViewport(0,0,width,height)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f,0f,0f,0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val aspect=width.toFloat()/height.coerceAtLeast(1)
            val proj=FloatArray(16); val view=FloatArray(16); val model=FloatArray(16); val vp=FloatArray(16); val mvp=FloatArray(16)
            Matrix.perspectiveM(proj,0,24f,aspect,.1f,20f)
            Matrix.setLookAtM(view,0,0f,-2.72f,4.3f,0f,0f,0f,0f,1f,0f)
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,0f,if(pressed).035f else 0f,if(pressed)-.16f else .18f)
            Matrix.rotateM(model,0,-8.2f + smoothPitch*.20f,1f,0f,0f)
            Matrix.rotateM(model,0,2.4f - smoothRoll*.24f,0f,1f,0f)
            Matrix.rotateM(model,0,smoothYaw*.025f,0f,0f,1f)
            Matrix.multiplyMM(vp,0,proj,0,view,0); Matrix.multiplyMM(mvp,0,vp,0,model,0)

            val lightAngle = normalize(baseLightAngle + smoothYaw*.42f + smoothRoll*.55f - smoothPitch*.22f)
            val rad=Math.toRadians(lightAngle.toDouble()); val lx=cos(rad).toFloat(); val ly=sin(rad).toFloat()
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0); GLES20.glUniformMatrix4fv(modelLoc,1,false,model,0)
            GLES20.glUniform3f(lightLoc,lx*1.55f,-ly*1.55f,2.15f)
            val blue=tuning.iceBlue.coerceIn(0f,1f)
            GLES20.glUniform3f(colorLoc,.16f-blue*.04f,.40f+blue*.18f,.67f+blue*.26f)
            GLES20.glUniform1f(alphaLoc,(.92f-tuning.transparency*.15f).coerceIn(.74f,.97f))
            val b=vertices?:return; val stride=7*4
            b.position(0); GLES20.glEnableVertexAttribArray(pLoc); GLES20.glVertexAttribPointer(pLoc,3,GLES20.GL_FLOAT,false,stride,b)
            b.position(3); GLES20.glEnableVertexAttribArray(nLoc); GLES20.glVertexAttribPointer(nLoc,3,GLES20.GL_FLOAT,false,stride,b)
            b.position(6); GLES20.glEnableVertexAttribArray(rLoc); GLES20.glVertexAttribPointer(rLoc,1,GLES20.GL_FLOAT,false,stride,b)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,count)
            GLES20.glDisableVertexAttribArray(pLoc); GLES20.glDisableVertexAttribArray(nLoc); GLES20.glDisableVertexAttribArray(rLoc)
        }

        private fun createProgram() {
            val vs=compile(GLES20.GL_VERTEX_SHADER,"""
uniform mat4 uMvp; uniform mat4 uModel; attribute vec3 aPosition; attribute vec3 aNormal; attribute float aRegion;
varying vec3 vN; varying vec3 vW; varying vec3 vO; varying float vR;
void main(){ vec4 w=uModel*vec4(aPosition,1.0); vW=w.xyz; vO=aPosition; vN=normalize(mat3(uModel)*aNormal); vR=aRegion; gl_Position=uMvp*vec4(aPosition,1.0); }
""")
            val fs=compile(GLES20.GL_FRAGMENT_SHADER,"""
precision highp float; uniform vec3 uLight; uniform vec3 uColor; uniform float uAlpha; varying vec3 vN; varying vec3 vW; varying vec3 vO; varying float vR;
void main(){
 vec3 N=normalize(vN), L=normalize(uLight), L2=normalize(vec3(-uLight.x*.72,uLight.y*.48,1.35));
 vec3 V=normalize(vec3(0.0,-2.72,4.30)-vW), H=normalize(L+V), H2=normalize(L2+V);
 float d=max(dot(N,L),0.0), d2=max(dot(N,L2),0.0), nv=max(dot(N,V),0.0);
 float fres=pow(1.0-nv,3.5), s1=pow(max(dot(N,H),0.0),150.0), s2=pow(max(dot(N,H2),0.0),110.0);
 float sw=pow(max(dot(N,H),0.0),20.0), side=smoothstep(1.25,2.55,vR), crown=clamp(1.0-abs(vR-1.0),0.0,1.0);
 vec3 body=uColor*(.12+d*.28+d2*.13)*( .72+vO.z*.22 );
 float flash=side*(s1*5.2+s2*3.6+sw*1.35+fres*1.75) + crown*(s1*2.5+s2*1.7);
 vec3 white=vec3(1.0,.995,.985)*flash;
 vec3 rim=vec3(.42,.78,1.0)*fres*(1.0+side*1.1);
 float phase=vO.x*9.0+vO.y*13.0+vO.z*5.0;
 vec3 fire=vec3(.95+.05*sin(phase),.70+.30*sin(phase+2.094),.88+.12*sin(phase+4.188))*clamp((sw+fres)*(side*.8+crown*.32),0.0,1.0)*.34;
 float mirrorA=pow(max(0.0,1.0-abs(dot(N,normalize(vec3(.80,-.18,.58))))),20.0);
 float mirrorB=pow(max(0.0,1.0-abs(dot(N,normalize(vec3(-.66,.28,.70))))),20.0);
 vec3 color=body+white+rim+fire+vec3(1.0)*side*(mirrorA*.9+mirrorB*.75);
 color=color/(color+vec3(.64)); color+=vec3(1.0)*clamp(flash*.18,0.0,.28); color=clamp(color,0.0,1.0);
 gl_FragColor=vec4(color,clamp(uAlpha+fres*.08+side*.05,.74,.99));
}
""")
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs); GLES20.glLinkProgram(program)
            val ok=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]==GLES20.GL_TRUE){GLES20.glGetProgramInfoLog(program)}
            pLoc=GLES20.glGetAttribLocation(program,"aPosition"); nLoc=GLES20.glGetAttribLocation(program,"aNormal"); rLoc=GLES20.glGetAttribLocation(program,"aRegion")
            mvpLoc=GLES20.glGetUniformLocation(program,"uMvp"); modelLoc=GLES20.glGetUniformLocation(program,"uModel"); lightLoc=GLES20.glGetUniformLocation(program,"uLight"); colorLoc=GLES20.glGetUniformLocation(program,"uColor"); alphaLoc=GLES20.glGetUniformLocation(program,"uAlpha")
        }
        private fun compile(type:Int,source:String):Int{ val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s); val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]==GLES20.GL_TRUE){GLES20.glGetShaderInfoLog(s)}; return s }

        private fun buildMesh(width:Int,height:Int){
            val aspect=width.toFloat()/height.coerceAtLeast(1); val hh=.92f; val hw=(hh*aspect).coerceIn(.92f,7.2f)
            val cx=(hh*.58f).coerceAtMost(hw*.24f); val cy=hh*.34f; val f=tuning.facetDepth.coerceIn(0f,1f); val bv=tuning.bevel.coerceIn(0f,1f)
            val outer=oct(hw,hh,cx,cy,-.02f); val crown=oct(hw-hh*(.16f+bv*.06f),hh*(.70f-bv*.04f),cx*.78f,cy*.78f,.24f+bv*.13f)
            val table=oct(hw-hh*(.38f+f*.04f),hh*(.47f-f*.03f),cx*.56f,cy*.52f,.37f+bv*.13f+f*.08f)
            val lower=Array(8){i->floatArrayOf(outer[i][0]*.92f,outer[i][1]*.90f,-.26f-f*.17f)}; val data=ArrayList<Float>()
            fun tri(a:FloatArray,b:FloatArray,c:FloatArray,region:Float){
                val ux=b[0]-a[0]; val uy=b[1]-a[1]; val uz=b[2]-a[2]; val vx=c[0]-a[0]; val vy=c[1]-a[1]; val vz=c[2]-a[2]
                var nx=uy*vz-uz*vy; var ny=uz*vx-ux*vz; var nz=ux*vy-uy*vx; val l=sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(.00001f); nx/=l; ny/=l; nz/=l
                arrayOf(a,b,c).forEach{v->data.add(v[0]);data.add(v[1]);data.add(v[2]);data.add(nx);data.add(ny);data.add(nz);data.add(region)}
            }
            fun ring(a:Array<FloatArray>,b:Array<FloatArray>,region:Float){for(i in 0..7){val n=(i+1)%8;tri(a[i],a[n],b[n],region);tri(a[i],b[n],b[i],region)}}
            val tc=floatArrayOf(0f,0f,table[0][2]); for(i in 0..7){val n=(i+1)%8;tri(table[i],table[n],tc,0f)}
            ring(table,crown,1f); ring(crown,outer,2f); ring(outer,lower,3f)
            val bc=floatArrayOf(0f,0f,lower[0][2]-.05f); for(i in 0..7){val n=(i+1)%8;tri(lower[n],lower[i],bc,3f)}
            count=data.size/7; vertices=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{data.forEach{put(it)};position(0)}
        }
        private fun oct(w:Float,h:Float,cx:Float,cy:Float,z:Float)=arrayOf(floatArrayOf(-w+cx,h,z),floatArrayOf(w-cx,h,z),floatArrayOf(w,h-cy,z),floatArrayOf(w,-h+cy,z),floatArrayOf(w-cx,-h,z),floatArrayOf(-w+cx,-h,z),floatArrayOf(-w,-h+cy,z),floatArrayOf(-w,h-cy,z))
        private fun normalize(v:Float)=((v%360f)+360f)%360f
        private fun shortestDelta(a:Float,b:Float)=((b-a+540f)%360f)-180f
    }
}

/** Un seul capteur téléphone partagé par tous les boutons 3D. */
private object DiamondMotionHub : SensorEventListener {
    private val listeners=WeakHashMap<True3DButtonHost,Unit>()
    private var manager:SensorManager?=null; private var sensor:Sensor?=null
    private val rot=FloatArray(9); private val ori=FloatArray(3)
    private var accelX=0f; private var accelY=0f; private var accelZ=0f
    fun attach(host:True3DButtonHost){
        listeners[host]=Unit; if(manager!=null)return
        val sm=host.context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager=sm; sensor=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let{sm.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}
    }
    fun detach(host:True3DButtonHost){listeners.remove(host);if(listeners.isEmpty()){manager?.unregisterListener(this);manager=null;sensor=null}}
    override fun onSensorChanged(e:SensorEvent){
        var pitch=0f; var roll=0f; var yaw=0f
        if(e.sensor.type==Sensor.TYPE_ROTATION_VECTOR){
            SensorManager.getRotationMatrixFromVector(rot,e.values); SensorManager.getOrientation(rot,ori)
            yaw=Math.toDegrees(ori[0].toDouble()).toFloat(); pitch=Math.toDegrees(ori[1].toDouble()).toFloat(); roll=Math.toDegrees(ori[2].toDouble()).toFloat()
        } else {
            val k=.15f; accelX+=(e.values[0]-accelX)*k; accelY+=(e.values[1]-accelY)*k; accelZ+=(e.values[2]-accelZ)*k
            pitch=Math.toDegrees(atan2(-accelY,sqrt(accelX*accelX+accelZ*accelZ).toDouble())).toFloat(); roll=Math.toDegrees(atan2(accelX,accelZ.toDouble())).toFloat(); yaw=0f
        }
        listeners.keys.toList().forEach{it.onDevicePose(pitch,roll,yaw)}
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
}

class True3DButtonHost(context:Context):FrameLayout(context){
    private val surface=True3DButtonTextureView(context); lateinit var button:Button; private set
    init{clipChildren=false;clipToPadding=false;addView(surface,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))}
    fun attachButton(value:Button,tuning:DiamondTuning,lightAngle:Float){button=value;surface.setCrystalTuning(tuning);surface.setLightAngle(lightAngle);addView(value,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));value.background=null;value.backgroundTintList=null;value.stateListAnimator=null;value.elevation=0f;value.translationZ=0f}
    fun setLightAngle(angle:Float)=surface.setLightAngle(angle)
    fun onDevicePose(pitch:Float,roll:Float,yaw:Float)=surface.setDevicePose(pitch,roll,yaw)
    override fun onAttachedToWindow(){super.onAttachedToWindow();DiamondMotionHub.attach(this)}
    override fun onDetachedFromWindow(){DiamondMotionHub.detach(this);super.onDetachedFromWindow()}
    override fun dispatchTouchEvent(ev:MotionEvent):Boolean{when(ev.actionMasked){MotionEvent.ACTION_DOWN->surface.setPressedDepth(true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->surface.setPressedDepth(false)};return super.dispatchTouchEvent(ev)}
}

object True3DButtonInstaller{
    private const val TAG="hp_true_3d_wrapped_v5_secondary_only"
    private val hosts=WeakHashMap<Button,True3DButtonHost>()

    fun install(root:View,lightAngle:Float){
        val list=ArrayList<Button>()
        collect(root,list)
        list.forEach{wrap(it,lightAngle)}
    }

    fun updateLight(root:View,lightAngle:Float){
        hosts.entries.toList().forEach{(b,h)->if(b.rootView===root.rootView)h.setLightAngle(lightAngle)}
    }

    private fun collect(v:View,out:MutableList<Button>){
        if(v is Button && v.getTag(R.id.true3d_internal_tag)!=TAG && !isPrimaryPointageButton(v)) out.add(v)
        if(v is ViewGroup && v !is True3DButtonHost) for(i in 0 until v.childCount) collect(v.getChildAt(i),out)
    }

    /** Entrée / Pause / Sortie restent volontairement inchangés pendant ce test. */
    private fun isPrimaryPointageButton(button:Button):Boolean{
        val name=runCatching{button.resources.getResourceEntryName(button.id)}.getOrNull().orEmpty()
        return name=="entryButton" || name=="pauseButton" || name=="exitButton"
    }

    private fun wrap(button:Button,lightAngle:Float){
        if(hosts.containsKey(button))return
        val parent=button.parent as? ViewGroup?:return
        if(parent is True3DButtonHost)return
        val i=parent.indexOfChild(button)
        val lp=button.layoutParams
        parent.removeViewAt(i)
        val host=True3DButtonHost(button.context)
        host.layoutParams=lp
        host.setTag(R.id.true3d_internal_tag,TAG)
        parent.addView(host,i)
        host.attachButton(button,DiamondTuningStore.load(button.context),lightAngle)
        button.setTag(R.id.true3d_internal_tag,TAG)
        hosts[button]=host
    }
}
