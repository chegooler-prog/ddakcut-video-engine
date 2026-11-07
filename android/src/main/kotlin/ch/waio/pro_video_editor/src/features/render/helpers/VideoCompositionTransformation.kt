package ch.waio.pro_video_editor.src.features.render.helpers

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/**
 * A GlEffect that handles positioning and scaling of a video segment
 * within a larger render canvas.
 *
 * It converts pixel-based offsets and sizes from the Flutter side into
 * the normalized OpenGL coordinates used by Media3 effects. The output frame is
 * the full canvas size; the video is drawn into the target rectangle and the
 * rest stays transparent, so the default Media3 compositor can alpha-blend the
 * layers on top of each other.
 *
 * When a clip box ([clipX], [clipY], [clipWidth], [clipHeight]) is provided, the
 * draw is scissored to that box (canvas pixels, top-left origin). This clips
 * `cover` overflow so a scaled-up clip cannot bleed past its target rectangle
 * onto other layers. When the clip box is `null`, no scissor is applied.
 */
@UnstableApi
class VideoCompositionTransformation(
    private val x: Double?,
    private val y: Double?,
    private val width: Double?,
    private val height: Double?,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val renderWidth: Int,
    private val renderHeight: Int,
    private val clipX: Double? = null,
    private val clipY: Double? = null,
    private val clipWidth: Double? = null,
    private val clipHeight: Double? = null,
    private val ddakcutChroma: Map<String, Any?>? = null
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr && ddakcutChroma != null) {
            throw VideoFrameProcessingException("DdakCut chroma currently requires SDR input")
        }
        return VideoCompositionShaderProgram(context, useHdr, this)
    }

    @UnstableApi
    private class VideoCompositionShaderProgram(
        context: Context,
        useHdr: Boolean,
        private val effect: VideoCompositionTransformation
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram

        companion object {
            private const val VERTEX_SHADER_SOURCE =
                "attribute vec4 aFramePosition;\n" +
                "attribute vec4 aTexSamplingCoord;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "void main() {\n" +
                "  gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "  vTexSamplingCoord = aTexSamplingCoord.xy;\n" +
                "}"

            private const val PLAIN_FRAGMENT_SHADER_SOURCE =
                "precision mediump float;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "}"

            private val FRAGMENT_SHADER_SOURCE = """
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform vec3 uKey;
uniform float uEnabled;
uniform float uSimilarity;
uniform float uSmoothness;
uniform float uSpill;
vec2 chroma(vec3 c) {
  return vec2(dot(c,vec3(-.168736,-.331264,.5)),dot(c,vec3(.5,-.418688,-.081312)));
}
// Media3 GlEffect SDR input/output is linear BT.709; key in display RGB.
float encode709(float x) { return x < .018 ? 4.5*x : 1.099*pow(max(x,0.0),.45)-.099; }
float decode709(float x) { return x < .081 ? x/4.5 : pow((x+.099)/1.099,1.0/.45); }
vec3 encodeRGB(vec3 c) { return vec3(encode709(c.r),encode709(c.g),encode709(c.b)); }
vec3 decodeRGB(vec3 c) { return vec3(decode709(c.r),decode709(c.g),decode709(c.b)); }
void main() {
  vec4 pixel=texture2D(uTexSampler,vTexSamplingCoord);
  if(uEnabled<.5){ gl_FragColor=pixel; return; }
  vec3 rgb=encodeRGB(clamp(pixel.rgb,0.0,1.0));
  vec2 c=chroma(rgb),k=chroma(uKey);
  float alpha=smoothstep(uSimilarity,uSimilarity+max(uSmoothness,.0001),distance(c,k));
  vec2 direction=normalize(k);
  c-=direction*max(0.0,dot(c,direction))*uSpill;
  float y=dot(rgb,vec3(.299,.587,.114));
  rgb=clamp(vec3(y+1.402*c.y,y-.344136*c.x-.714136*c.y,y+1.772*c.x),0.0,1.0);
  gl_FragColor=vec4(decodeRGB(rgb),pixel.a*alpha);
}
""".trimIndent()
        }

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER_SOURCE, if (effect.ddakcutChroma == null) PLAIN_FRAGMENT_SHADER_SOURCE else FRAGMENT_SHADER_SOURCE)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            return Size(effect.renderWidth, effect.renderHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                if (effect.ddakcutChroma != null) {
                val key = effect.ddakcutChroma
                val enabled = key?.get("enabled") == true
                val argb = (key?.get("color") as? Number)?.toLong() ?: 0xFF00B140L
                val similarity = (key?.get("similarity") as? Number)?.toFloat() ?: 0.2f
                val smoothness = (key?.get("smoothness") as? Number)?.toFloat() ?: 0.08f
                val spill = (key?.get("spill") as? Number)?.toFloat() ?: 0.5f
                require(!enabled || (argb == 0xFF00B140L || argb == 0xFF0047BBL)) { "Invalid chroma color" }
                require(!enabled || (similarity.isFinite() && similarity in 0.01f..0.6f &&
                    smoothness.isFinite() && smoothness in 0.001f..0.3f &&
                    spill.isFinite() && spill in 0f..1f)) { "Invalid chroma settings" }
                glProgram.setFloatsUniform("uEnabled", floatArrayOf(if (enabled) 1f else 0f))
                glProgram.setFloatsUniform("uKey", floatArrayOf(
                    ((argb shr 16) and 255).toFloat()/255f,
                    ((argb shr 8) and 255).toFloat()/255f,
                    (argb and 255).toFloat()/255f))
                glProgram.setFloatsUniform("uSimilarity", floatArrayOf(similarity))
                glProgram.setFloatsUniform("uSmoothness", floatArrayOf(smoothness))
                glProgram.setFloatsUniform("uSpill", floatArrayOf(spill))
                }

                // Clear the target framebuffer to transparent before drawing the
                // segment, so segments that don't cover the full canvas don't
                // show garbage.
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // Enable alpha blending to support transparent layers/overlays.
                if (effect.ddakcutChroma != null) {
                    // Preserve straight RGB and alpha for Media3's compositor.
                    GLES20.glDisable(GLES20.GL_BLEND)
                } else {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                }

                val glMatrix = FloatArray(16)
                Matrix.setIdentityM(glMatrix, 0)

                val targetWidth = (effect.width ?: effect.videoWidth.toDouble()).toFloat()
                val targetHeight = (effect.height ?: effect.videoHeight.toDouble()).toFloat()

                // sx and sy are half-widths in NDC (relative to a 2.0 wide space)
                val sx = if (effect.renderWidth > 0) targetWidth / effect.renderWidth else 1.0f
                val sy = if (effect.renderHeight > 0) targetHeight / effect.renderHeight else 1.0f

                // Convert pixel (x, y) to NDC top-left
                val leftNDC =
                    if (effect.renderWidth > 0) {
                        (2f * (effect.x ?: 0.0).toFloat() / effect.renderWidth) - 1f
                    } else {
                        -1.0f
                    }
                val topNDC =
                    if (effect.renderHeight > 0) {
                        1f - (2f * (effect.y ?: 0.0).toFloat() / effect.renderHeight)
                    } else {
                        1.0f
                    }

                // Target center in NDC for a quad that is 2x2 centered at 0,0
                val centerX = leftNDC + sx
                val centerY = topNDC - sy

                Matrix.translateM(glMatrix, 0, centerX, centerY, 0f)
                Matrix.scaleM(glMatrix, 0, sx, sy, 1f)

                glProgram.setFloatsUniform("uTransformationMatrix", glMatrix)
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)

                // Set attribute buffers with robust size detection
                val vertexData = GlUtil.getNormalizedCoordinateBounds()
                val vertexSize = if (vertexData.size == 8) 2 else 4
                glProgram.setBufferAttribute("aFramePosition", vertexData, vertexSize)

                val texData = GlUtil.getTextureCoordinateBounds()
                val texSize = if (texData.size == 8) 2 else 4
                glProgram.setBufferAttribute("aTexSamplingCoord", texData, texSize)

                glProgram.bindAttributesAndUniforms()

                // Clip the draw to the target box so `cover` overflow (a scaled
                // up clip larger than its rect) can't bleed onto other layers.
                val clipW = effect.clipWidth
                val clipH = effect.clipHeight
                val applyScissor = effect.clipX != null && effect.clipY != null &&
                    clipW != null && clipH != null && effect.renderHeight > 0
                if (applyScissor) {
                    // Convert the clip box (canvas px, top-left origin) to GL
                    // scissor space (px, bottom-left origin).
                    val sx = effect.clipX!!.roundToInt()
                    val sw = clipW!!.roundToInt().coerceAtLeast(0)
                    val sh = clipH!!.roundToInt().coerceAtLeast(0)
                    val sy = (effect.renderHeight - (effect.clipY!! + clipH!!)).roundToInt()
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(sx, sy, sw, sh)
                }

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                if (applyScissor) {
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                }
                GLES20.glDisable(GLES20.GL_BLEND)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }
    }
}
