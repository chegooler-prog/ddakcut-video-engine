package ch.waio.pro_video_editor

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android GLES2 texture output: independent of Flutter's Skia/Impeller backend. */
@UnstableApi
internal class DdakCutChromaPreview(
    private val context: Context,
    private val textures: TextureRegistry
) {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<Long, Preview>()

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        if (!call.method.startsWith("ddakcutPreview")) return false
        try {
            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
            if (call.method == "ddakcutPreviewCreate") {
                val path = args["path"] as? String ?: error("Missing video path")
                require(File(path).isFile) { "Video file is unavailable" }
                require(sessions.size < 5) { "Too many preview players" }
                val entry = textures.createSurfaceTexture()
                val preview = Preview(entry, result)
                sessions[entry.id()] = preview
                preview.open(path)
            } else {
                val id = (args["id"] as? Number)?.toLong() ?: error("Missing preview id")
                val p = sessions[id]
                when (call.method) {
                    "ddakcutPreviewDispose" -> {
                        sessions.remove(id)?.release()
                        result.success(null)
                    }
                    "ddakcutPreviewSync" -> {
                        require(p != null) { "Preview has been released" }
                        p.sync(args)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        } catch (e: Exception) {
            result.error("CHROMA_PREVIEW", e.message, null)
        }
        return true
    }

    fun release() {
        sessions.values.toList().forEach { it.release() }
        sessions.clear()
    }

    private inner class Preview(
        private val entry: TextureRegistry.SurfaceTextureEntry,
        private var pending: MethodChannel.Result?
    ) {
        private var player: ExoPlayer? = null
        private var renderer: Renderer? = null
        private var closed = false
        private var lastTarget = -1L
        private var failure: String? = null
        private var aspect = 1.0
        private val timeout = Runnable { fail("Preview preparation timed out") }

        fun open(path: String) {
            main.postDelayed(timeout, 30_000)
            renderer = Renderer(entry.surfaceTexture(), { surface ->
                main.post {
                    if (!closed) {
                        try {
                            val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
                            player = p
                            p.volume = 0f
                            p.addListener(object : Player.Listener {
                                override fun onVideoSizeChanged(size: VideoSize) {
                                    if (size.width > 0 && size.height > 0) {
                                        aspect = size.width.toDouble() * size.pixelWidthHeightRatio / size.height
                                        renderer?.resize(size.width, size.height)
                                        completeWhenReady()
                                    }
                                }
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_READY && !closed) {
                                        completeWhenReady()
                                    }
                                }
                                override fun onPlayerError(error: PlaybackException) {
                                    fail(error.message ?: "Video decoder failed")
                                }
                            })
                            p.setVideoSurface(surface)
                            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                            p.prepare()
                        } catch (e: Exception) { fail(e.message ?: "Preview failed") }
                    }
                }
            }, { message -> main.post { fail(message) } })
        }

        private fun completeWhenReady() {
            val p = player ?: return
            if (closed || p.playbackState != Player.STATE_READY || p.videoSize.width <= 0 || p.videoSize.height <= 0) return
            main.removeCallbacks(timeout)
            pending?.success(mapOf("id" to entry.id(), "aspect" to aspect))
            pending = null
        }

        fun sync(args: Map<*, *>) {
            failure?.let { error(it) }
            check(!closed) { "Preview has been released" }
            val p = player ?: return
            val target = ((args["positionMs"] as? Number)?.toLong() ?: 0L)
                .coerceAtLeast(0L).let { if (p.duration > 0) it.coerceAtMost(p.duration - 1) else it }
            val playing = args["playing"] == true
            val drift = abs(p.currentPosition - target)
            if (lastTarget < 0 || (!playing && target != lastTarget) || (playing && drift > 300L)) p.seekTo(target)
            lastTarget = target
            p.volume = ((args["volume"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
            p.playWhenReady = playing
            @Suppress("UNCHECKED_CAST")
            val key = args["settings"] as? Map<*, *>
            renderer?.settings(key)
        }

        private fun fail(message: String) {
            if (closed) return
            failure = message
            pending?.error("CHROMA_PREVIEW", message, null)
            pending = null
            sessions.remove(entry.id())
            release()
        }

        fun release() {
            if (closed) return
            closed = true
            main.removeCallbacks(timeout)
            pending?.error("CHROMA_PREVIEW", "Preview was closed", null)
            pending = null
            player?.release()
            player = null
            renderer?.release { main.post { entry.release() } }
                ?: entry.release()
            renderer = null
        }
    }

    private class Renderer(
        private val output: SurfaceTexture,
        private val ready: (Surface) -> Unit,
        private val failed: (String) -> Unit
    ) {
        private val thread = HandlerThread("DdakCutChromaGL").apply { start() }
        private val handler = Handler(thread.looper)
        private var display = EGL14.EGL_NO_DISPLAY
        private var context = EGL14.EGL_NO_CONTEXT
        private var window = EGL14.EGL_NO_SURFACE
        private var outputSurface: Surface? = null
        private var input: SurfaceTexture? = null
        private var inputSurface: Surface? = null
        private var texture = 0
        private var program = 0
        private var width = 720
        private var height = 720
        private var frame = false
        @Volatile private var closed = false
        private var values = floatArrayOf(0f, 177f/255f, 64f/255f, .2f, .08f, .5f)
        private val transform = FloatArray(16)
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
        }

        init { handler.post {
            try { initialize(); ready(inputSurface!!) }
            catch (e: Exception) { failed(e.message ?: "OpenGL preview initialization failed") }
        } }

        private fun initialize() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, IntArray(1), 0))
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            output.setDefaultBufferSize(width,height)
            outputSurface = Surface(output)
            window = EGL14.eglCreateWindowSurface(display,config,outputSurface,intArrayOf(EGL14.EGL_NONE),0)
            check(EGL14.eglMakeCurrent(display,window,window,context))
            program = GLES20.glCreateProgram()
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
            GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            val ids = IntArray(1); GLES20.glGenTextures(1,ids,0); texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            input = SurfaceTexture(texture).apply {
                setOnFrameAvailableListener({ draw(true) }, handler)
            }
            inputSurface = Surface(input)
            Matrix.setIdentityM(transform,0)
        }
        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
            val status = IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        fun resize(w: Int, h: Int) { handler.post {
            if (!closed) {
                val scale = minOf(1.0,720.0 / maxOf(w,h))
                width = (w*scale).roundToInt().coerceAtLeast(1)
                height = (h*scale).roundToInt().coerceAtLeast(1)
                output.setDefaultBufferSize(width,height)
                draw(false)
            }
        } }
        fun settings(key: Map<*, *>?) {
            val argb = (key?.get("color") as? Number)?.toLong() ?: 0xFF00B140L
            val next = floatArrayOf(((argb shr 16) and 255)/255f,((argb shr 8) and 255)/255f,(argb and 255)/255f,
                (key?.get("similarity") as? Number)?.toFloat() ?: .2f,
                (key?.get("smoothness") as? Number)?.toFloat() ?: .08f,
                (key?.get("spill") as? Number)?.toFloat() ?: .5f)
            require(next.all { it.isFinite() })
            handler.post { if (!closed && !next.contentEquals(values)) { values = next; draw(false) } }
        }
        private fun draw(update: Boolean) {
            if (closed) return
            try {
                if (update) { input?.updateTexImage(); input?.getTransformMatrix(transform); frame = true }
                if (!frame) return
                GLES20.glViewport(0,0,width,height)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glClearColor(0f,0f,0f,0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMatrix"),1,false,transform,0)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(program,"uKey"),values[0],values[1],values[2])
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uSimilarity"),values[3])
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uSmoothness"),values[4])
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uSpill"),values[5])
                val pos = GLES20.glGetAttribLocation(program,"aPosition")
                val uv = GLES20.glGetAttribLocation(program,"aUv")
                quad.position(0); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,quad)
                quad.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,quad)
                GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(uv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                check(EGL14.eglSwapBuffers(display,window)) { "Preview surface lost" }
            } catch (e: Exception) { failed(e.message ?: "Preview rendering failed") }
        }
        fun release(done: () -> Unit) {
            closed = true
            handler.post {
                try {
                    input?.setOnFrameAvailableListener(null)
                    inputSurface?.release(); input?.release()
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
                        EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
                    }
                    outputSurface?.release()
                } finally { done(); thread.quitSafely() }
            }
        }
        companion object {
            private const val VERTEX = """
attribute vec2 aPosition;
attribute vec2 aUv;
uniform mat4 uMatrix;
varying vec2 vUv;
void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=(uMatrix*vec4(aUv,0.0,1.0)).xy;}
"""
            private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform vec3 uKey;
uniform float uSimilarity;
uniform float uSmoothness;
uniform float uSpill;
varying vec2 vUv;
vec2 chroma(vec3 c){return vec2(dot(c,vec3(-.168736,-.331264,.5)),dot(c,vec3(.5,-.418688,-.081312)));}
void main(){
 vec4 pixel=texture2D(uTexture,vUv);vec3 rgb=pixel.rgb;
 vec2 c=chroma(rgb),k=chroma(uKey);
 float a=pixel.a*smoothstep(uSimilarity,uSimilarity+max(uSmoothness,.0001),distance(c,k));
 vec2 direction=normalize(k);c-=direction*max(0.0,dot(c,direction))*uSpill;
 float y=dot(rgb,vec3(.299,.587,.114));
 rgb=clamp(vec3(y+1.402*c.y,y-.344136*c.x-.714136*c.y,y+1.772*c.x),0.0,1.0);
 gl_FragColor=vec4(rgb*a,a);
}
"""
        }
    }
}
