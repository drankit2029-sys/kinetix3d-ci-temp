/*
 * Kinetix3D Studio — engine-render
 * Phase 2 kernels: pass-through, ColorSuite and separable blur.
 */

package com.kinetix3d.render

import android.opengl.GLES11Ext
import android.opengl.GLES20

class PassThroughKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        return textureLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        tileOffsetX: Float,
        tileOffsetY: Float,
        tileScaleX: Float,
        tileScaleY: Float,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(tileOffsetX, tileOffsetY, tileScaleX, tileScaleY)
        GLES20.glUniform1i(textureLocation, 0)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
        """
    }
}

class ExternalPassThroughKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var matrixLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        matrixLocation = GLES20.glGetUniformLocation(quad.programId, "uTexMatrix")
        return textureLocation >= 0 && matrixLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        tileOffsetX: Float,
        tileOffsetY: Float,
        tileScaleX: Float,
        tileScaleY: Float,
        textureMatrix: FloatArray?,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        quad.use(tileOffsetX, tileOffsetY, tileScaleX, tileScaleY)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix ?: IDENTITY, 0)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() = quad.release()

    companion object {
        private val IDENTITY =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            void main() {
                vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(uTexture, uv);
            }
        """
    }
}

class ColorSuiteKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var contrastLocation = -1
    private var saturationLocation = -1
    private var temperatureLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        contrastLocation = GLES20.glGetUniformLocation(quad.programId, "uContrast")
        saturationLocation = GLES20.glGetUniformLocation(quad.programId, "uSaturation")
        temperatureLocation = GLES20.glGetUniformLocation(quad.programId, "uTemperature")
        return textureLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        tileOffsetX: Float,
        tileOffsetY: Float,
        tileScaleX: Float,
        tileScaleY: Float,
        contrast: Float,
        saturation: Float,
        temperature: Float,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(tileOffsetX, tileOffsetY, tileScaleX, tileScaleY)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(contrastLocation, contrast)
        GLES20.glUniform1f(saturationLocation, saturation)
        GLES20.glUniform1f(temperatureLocation, temperature)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            void main() {
                vec4 color = texture2D(uTexture, vUv);
                color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
                float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                color.rgb = mix(vec3(luma), color.rgb, uSaturation);
                color.rgb += vec3(uTemperature * 0.1, 0.0, -uTemperature * 0.1);
                gl_FragColor = color;
            }
        """
    }
}

class BlurKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var directionLocation = -1
    private var texelLocation = -1
    private var radiusLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        directionLocation = GLES20.glGetUniformLocation(quad.programId, "uDirection")
        texelLocation = GLES20.glGetUniformLocation(quad.programId, "uTexel")
        radiusLocation = GLES20.glGetUniformLocation(quad.programId, "uRadius")
        return textureLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        pool: ReferenceCountedFboPool,
        width: Int,
        height: Int,
        radiusPx: Float,
    ): Boolean {
        val temp = pool.acquire(width, height) ?: return false
        drawPass(inputTexture, temp.framebufferId, width, height, 1f, 0f, radiusPx)
        drawPass(temp.textureId, outputFramebuffer, width, height, 0f, 1f, radiusPx)
        pool.release(temp)
        return true
    }

    private fun drawPass(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        directionX: Float,
        directionY: Float,
        radiusPx: Float,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform2f(directionLocation, directionX, directionY)
        GLES20.glUniform2f(texelLocation, 1f / width, 1f / height)
        GLES20.glUniform1f(radiusLocation, radiusPx)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            uniform vec2 uTexel;
            uniform float uRadius;
            void main() {
                vec4 sum = vec4(0.0);
                float total = 0.0;
                for (int i = -4; i <= 4; i++) {
                    float fi = float(i);
                    float weight = exp(-0.5 * (fi * fi) / 4.0);
                    vec2 offset = uDirection * uTexel * (uRadius * fi / 4.0);
                    sum += texture2D(uTexture, vUv + offset) * weight;
                    total += weight;
                }
                gl_FragColor = sum / total;
            }
        """
    }
}

class TilesKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var scaleLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        scaleLocation = GLES20.glGetUniformLocation(quad.programId, "uScale")
        return textureLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        gridX: Float,
        gridY: Float,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform2f(scaleLocation, gridX, gridY)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform vec2 uScale;
            void main() {
                vec2 tiled = abs(mod(vUv * uScale, 2.0) - 1.0);
                gl_FragColor = texture2D(uTexture, tiled);
            }
        """
    }
}

class GlowKernel {
    private val thresholdQuad = FullscreenQuad()
    private val addQuad = FullscreenQuad()
    private val blur = BlurKernel()
    private var thresholdTextureLocation = -1
    private var thresholdValueLocation = -1
    private var addBaseLocation = -1
    private var addGlowLocation = -1
    private var addIntensityLocation = -1

    fun initialize(): Boolean {
        if (!thresholdQuad.create(THRESHOLD_SHADER)) return false
        if (!addQuad.create(ADD_SHADER)) return false
        if (!blur.initialize()) return false
        thresholdTextureLocation = GLES20.glGetUniformLocation(thresholdQuad.programId, "uTexture")
        thresholdValueLocation = GLES20.glGetUniformLocation(thresholdQuad.programId, "uThreshold")
        addBaseLocation = GLES20.glGetUniformLocation(addQuad.programId, "uBase")
        addGlowLocation = GLES20.glGetUniformLocation(addQuad.programId, "uGlow")
        addIntensityLocation = GLES20.glGetUniformLocation(addQuad.programId, "uIntensity")
        return thresholdTextureLocation >= 0 && addBaseLocation >= 0 && addGlowLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        pool: ReferenceCountedFboPool,
        width: Int,
        height: Int,
        threshold: Float,
        intensity: Float,
        radiusPx: Float,
    ): Boolean {
        val bright = pool.acquire(width, height) ?: return false
        val blurred =
            pool.acquire(width, height) ?: run {
                pool.release(bright)
                return false
            }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bright.framebufferId)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        thresholdQuad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(thresholdTextureLocation, 0)
        GLES20.glUniform1f(thresholdValueLocation, threshold)
        thresholdQuad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val ok = blur.render(bright.textureId, blurred.framebufferId, pool, width, height, radiusPx)
        if (!ok) {
            pool.release(bright)
            pool.release(blurred)
            return false
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurred.textureId)
        addQuad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(addBaseLocation, 0)
        GLES20.glUniform1i(addGlowLocation, 1)
        GLES20.glUniform1f(addIntensityLocation, intensity)
        addQuad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        pool.release(bright)
        pool.release(blurred)
        return true
    }

    fun release() {
        thresholdQuad.release()
        addQuad.release()
        blur.release()
    }

    companion object {
        private const val THRESHOLD_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uThreshold;
            void main() {
                vec4 color = texture2D(uTexture, vUv);
                float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                float mask = smoothstep(uThreshold, uThreshold + 0.2, luma);
                gl_FragColor = vec4(color.rgb * mask, color.a);
            }
        """

        private const val ADD_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uBase;
            uniform sampler2D uGlow;
            uniform float uIntensity;
            void main() {
                vec4 base = texture2D(uBase, vUv);
                vec4 glow = texture2D(uGlow, vUv);
                gl_FragColor = vec4(base.rgb + glow.rgb * uIntensity, base.a);
            }
        """
    }
}

class WarpKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var amplitudeLocation = -1
    private var frequencyLocation = -1
    private var seedLocation = -1
    private var timeLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        amplitudeLocation = GLES20.glGetUniformLocation(quad.programId, "uAmplitude")
        frequencyLocation = GLES20.glGetUniformLocation(quad.programId, "uFrequency")
        seedLocation = GLES20.glGetUniformLocation(quad.programId, "uSeed")
        timeLocation = GLES20.glGetUniformLocation(quad.programId, "uTime")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        amplitude: Float,
        frequency: Float,
        seed: Float,
        timeSeconds: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(amplitudeLocation, amplitude)
        GLES20.glUniform1f(frequencyLocation, frequency)
        GLES20.glUniform1f(seedLocation, seed)
        GLES20.glUniform1f(timeLocation, timeSeconds)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uAmplitude;
            uniform float uFrequency;
            uniform float uSeed;
            uniform float uTime;
            uniform int uMode;
            void main() {
                vec2 uv = vUv;
                vec2 centered = uv - 0.5;
                vec2 offset = vec2(0.0);
                float phase = uFrequency * 6.2831853;
                if (uMode == 0) {
                    offset.x = sin(uv.y * phase + uTime + uSeed) * uAmplitude;
                    offset.y = cos(uv.x * phase + uTime * 1.3 + uSeed) * uAmplitude;
                } else if (uMode == 1) {
                    offset.x = sin(uv.y * phase + uSeed) * uAmplitude;
                    offset.y = cos(uv.x * phase + uSeed) * uAmplitude;
                } else if (uMode == 2) {
                    offset.x = sin(uv.y * phase + uTime) * uAmplitude;
                } else if (uMode == 3) {
                    float r = length(centered);
                    float angle = uAmplitude * 10.0 * (1.0 - smoothstep(0.0, 0.7, r));
                    float s = sin(angle);
                    float c = cos(angle);
                    uv = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c) + 0.5;
                } else if (uMode == 4) {
                    float r = length(centered);
                    float k = 1.0 + uAmplitude * 4.0 * (1.0 - smoothstep(0.0, 0.7, r));
                    uv = centered / k + 0.5;
                } else if (uMode == 5) {
                    offset.x = sin(uv.y * 3.0 + uSeed) * uAmplitude;
                    offset.y = cos(uv.x * 3.0 + uSeed) * uAmplitude;
                } else {
                    float r = length(centered);
                    float angle = uAmplitude * 6.0 * exp(-r * 4.0);
                    float s = sin(angle);
                    float c = cos(angle);
                    uv = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c) + 0.5;
                }
                uv += offset;
                gl_FragColor = texture2D(uTexture, clamp(uv, 0.0, 1.0));
            }
        """
    }
}

class GlitchKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var intensityLocation = -1
    private var seedLocation = -1
    private var blockSizeLocation = -1
    private var timeLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        intensityLocation = GLES20.glGetUniformLocation(quad.programId, "uIntensity")
        seedLocation = GLES20.glGetUniformLocation(quad.programId, "uSeed")
        blockSizeLocation = GLES20.glGetUniformLocation(quad.programId, "uBlockSize")
        timeLocation = GLES20.glGetUniformLocation(quad.programId, "uTime")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        intensity: Float,
        seed: Float,
        blockSize: Float,
        timeSeconds: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(intensityLocation, intensity)
        GLES20.glUniform1f(seedLocation, seed)
        GLES20.glUniform1f(blockSizeLocation, blockSize)
        GLES20.glUniform1f(timeLocation, timeSeconds)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uIntensity;
            uniform float uSeed;
            uniform float uBlockSize;
            uniform float uTime;
            uniform int uMode;
            float hash(float n) {
                return fract(sin(n + uSeed) * 43758.5453);
            }
            void main() {
                vec2 uv = vUv;
                if (uMode == 0) {
                    float d = uIntensity * 0.02;
                    float r = texture2D(uTexture, uv + vec2(d, 0.0)).r;
                    float g = texture2D(uTexture, uv).g;
                    float b = texture2D(uTexture, uv - vec2(d, 0.0)).b;
                    gl_FragColor = vec4(r, g, b, 1.0);
                } else if (uMode == 1 || uMode == 3) {
                    float block = max(uBlockSize, 0.001);
                    float row = floor(uv.y / block);
                    float shift = (hash(row) - 0.5) * uIntensity * 0.2;
                    gl_FragColor = texture2D(uTexture, vec2(fract(uv.x + shift), uv.y));
                } else if (uMode == 2) {
                    float line = sin(uv.y * 800.0 + uTime * 20.0) * 0.5 + 0.5;
                    vec3 color = texture2D(uTexture, uv).rgb;
                    gl_FragColor = vec4(color * (1.0 - uIntensity * 0.5 * line), 1.0);
                } else if (uMode == 4) {
                    float d = uIntensity * 0.01;
                    vec3 color;
                    color.r = texture2D(uTexture, uv + vec2(d, 0.0)).r;
                    color.g = texture2D(uTexture, uv).g;
                    color.b = texture2D(uTexture, uv - vec2(d, 0.0)).b;
                    float line = sin(uv.y * 600.0) * 0.5 + 0.5;
                    gl_FragColor = vec4(color * (1.0 - uIntensity * 0.3 * line), 1.0);
                } else {
                    float slice = floor(uv.y / max(uBlockSize, 0.001));
                    float shift = (hash(slice) - 0.5) * uIntensity * 0.3;
                    gl_FragColor = texture2D(uTexture, vec2(fract(uv.x + shift), uv.y));
                }
            }
        """
    }
}

class VectorifyKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var levelsLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        levelsLocation = GLES20.glGetUniformLocation(quad.programId, "uLevels")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && levelsLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        levels: Int,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1i(levelsLocation, levels)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform int uLevels;
            uniform int uMode;
            vec3 srgbToLinear(vec3 c) {
                return pow(max(c, 0.0), vec3(2.2));
            }
            vec3 linearToSrgb(vec3 c) {
                return pow(max(c, 0.0), vec3(1.0 / 2.2));
            }
            vec3 linearToOklab(vec3 c) {
                float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
                float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
                float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
                l = pow(max(l, 0.0), 1.0 / 3.0);
                m = pow(max(m, 0.0), 1.0 / 3.0);
                s = pow(max(s, 0.0), 1.0 / 3.0);
                return vec3(
                    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
                );
            }
            vec3 oklabToLinear(vec3 lab) {
                float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
                float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
                float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
                l = l * l * l;
                m = m * m * m;
                s = s * s * s;
                return vec3(
                    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
                );
            }
            void main() {
                vec3 color = texture2D(uTexture, vUv).rgb;
                if (uMode == 0) {
                    float step = 1.0 / 512.0;
                    vec3 sum = color;
                    sum += texture2D(uTexture, vUv + vec2(step, 0.0)).rgb;
                    sum += texture2D(uTexture, vUv - vec2(step, 0.0)).rgb;
                    sum += texture2D(uTexture, vUv + vec2(0.0, step)).rgb;
                    sum += texture2D(uTexture, vUv - vec2(0.0, step)).rgb;
                    color = sum / 5.0;
                }
                vec3 lab = linearToOklab(srgbToLinear(color));
                float levels = max(float(uLevels), 2.0);
                lab.x = floor(lab.x * levels + 0.5) / levels;
                lab.y = floor(lab.y * levels + 0.5) / levels;
                lab.z = floor(lab.z * levels + 0.5) / levels;
                gl_FragColor = vec4(linearToSrgb(oklabToLinear(lab)), 1.0);
            }
        """
    }
}

class KeyerKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var toleranceLocation = -1
    private var softnessLocation = -1
    private var despillLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        toleranceLocation = GLES20.glGetUniformLocation(quad.programId, "uTolerance")
        softnessLocation = GLES20.glGetUniformLocation(quad.programId, "uSoftness")
        despillLocation = GLES20.glGetUniformLocation(quad.programId, "uDespill")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        tolerance: Float,
        softness: Float,
        despill: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(toleranceLocation, tolerance)
        GLES20.glUniform1f(softnessLocation, softness)
        GLES20.glUniform1f(despillLocation, despill)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uTolerance;
            uniform float uSoftness;
            uniform float uDespill;
            uniform int uMode;
            void main() {
                vec4 color = texture2D(uTexture, vUv);
                float alpha;
                if (uMode == 2) {
                    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float d = abs(luma - 0.5);
                    alpha = smoothstep(uTolerance, uTolerance + uSoftness, d);
                } else {
                    float d = distance(color.rgb, vec3(0.0, 1.0, 0.0));
                    alpha = smoothstep(uTolerance, uTolerance + uSoftness, d);
                }
                vec3 rgb = color.rgb;
                if (uDespill > 0.0) {
                    float green = rgb.g - max(rgb.r, rgb.b);
                    rgb.g -= max(green, 0.0) * uDespill;
                }
                vec3 checker = mix(vec3(0.12), vec3(0.22), mod(floor(vUv.x * 40.0) + floor(vUv.y * 40.0), 2.0));
                gl_FragColor = vec4(mix(checker, rgb, alpha), 1.0);
            }
        """
    }
}

class CompositeKernel {
    private val quad2d = FullscreenQuad()
    private val quadExternal = FullscreenQuad()
    private var base2d = -1
    private var overlay2d = -1
    private var opacity2d = -1
    private var matrix2d = -1
    private var blendMode2d = -1
    private var baseExt = -1
    private var overlayExt = -1
    private var opacityExt = -1
    private var matrixExt = -1
    private var blendModeExt = -1

    fun initialize(): Boolean {
        if (!quad2d.create(FRAGMENT_SHADER_2D)) return false
        if (!quadExternal.create(FRAGMENT_SHADER_EXTERNAL)) return false
        base2d = GLES20.glGetUniformLocation(quad2d.programId, "uBase")
        overlay2d = GLES20.glGetUniformLocation(quad2d.programId, "uOverlay")
        opacity2d = GLES20.glGetUniformLocation(quad2d.programId, "uOpacity")
        matrix2d = GLES20.glGetUniformLocation(quad2d.programId, "uOverlayMatrix")
        blendMode2d = GLES20.glGetUniformLocation(quad2d.programId, "uBlendMode")
        baseExt = GLES20.glGetUniformLocation(quadExternal.programId, "uBase")
        overlayExt = GLES20.glGetUniformLocation(quadExternal.programId, "uOverlay")
        opacityExt = GLES20.glGetUniformLocation(quadExternal.programId, "uOpacity")
        matrixExt = GLES20.glGetUniformLocation(quadExternal.programId, "uOverlayMatrix")
        blendModeExt = GLES20.glGetUniformLocation(quadExternal.programId, "uBlendMode")
        return base2d >= 0 && overlay2d >= 0 && baseExt >= 0 && overlayExt >= 0
    }

    fun render(
        baseTexture: Int,
        overlayTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        opacity: Float,
        overlayExternal: Boolean,
        overlayMatrix: FloatArray?,
        blendMode: Int = 0,
    ) {
        val quad = if (overlayExternal) quadExternal else quad2d
        val baseLoc = if (overlayExternal) baseExt else base2d
        val overlayLoc = if (overlayExternal) overlayExt else overlay2d
        val opacityLoc = if (overlayExternal) opacityExt else opacity2d
        val matrixLoc = if (overlayExternal) matrixExt else matrix2d
        val blendLoc = if (overlayExternal) blendModeExt else blendMode2d
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(
            if (overlayExternal) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
            overlayTexture,
        )
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(baseLoc, 0)
        GLES20.glUniform1i(overlayLoc, 1)
        GLES20.glUniform1f(opacityLoc, opacity)
        GLES20.glUniformMatrix4fv(matrixLoc, 1, false, overlayMatrix ?: IDENTITY, 0)
        GLES20.glUniform1i(blendLoc, blendMode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(
            if (overlayExternal) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
            0,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() {
        quad2d.release()
        quadExternal.release()
    }

    companion object {
        private val IDENTITY =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uBase;
            uniform sampler2D uOverlay;
            uniform float uOpacity;
            uniform mat4 uOverlayMatrix;
            uniform int uBlendMode;
            vec3 blend(vec3 base, vec3 overlay) {
                if (uBlendMode == 1) return 1.0 - (1.0 - base) * (1.0 - overlay);
                if (uBlendMode == 2) return base * overlay;
                if (uBlendMode == 3) return min(base + overlay, vec3(1.0));
                return overlay;
            }
            void main() {
                vec4 base = texture2D(uBase, vUv);
                vec2 uv = (uOverlayMatrix * vec4(vUv, 0.0, 1.0)).xy;
                vec4 overlay = texture2D(uOverlay, uv);
                vec3 blended = blend(base.rgb, overlay.rgb);
                gl_FragColor = vec4(mix(base.rgb, blended, overlay.a * uOpacity), 1.0);
            }
        """

        private const val FRAGMENT_SHADER_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uBase;
            uniform samplerExternalOES uOverlay;
            uniform float uOpacity;
            uniform mat4 uOverlayMatrix;
            uniform int uBlendMode;
            vec3 blend(vec3 base, vec3 overlay) {
                if (uBlendMode == 1) return 1.0 - (1.0 - base) * (1.0 - overlay);
                if (uBlendMode == 2) return base * overlay;
                if (uBlendMode == 3) return min(base + overlay, vec3(1.0));
                return overlay;
            }
            void main() {
                vec4 base = texture2D(uBase, vUv);
                vec2 uv = (uOverlayMatrix * vec4(vUv, 0.0, 1.0)).xy;
                vec4 overlay = texture2D(uOverlay, uv);
                vec3 blended = blend(base.rgb, overlay.rgb);
                gl_FragColor = vec4(mix(base.rgb, blended, overlay.a * uOpacity), 1.0);
            }
        """
    }
}

class OscillateKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var amplitudeLocation = -1
    private var frequencyLocation = -1
    private var seedLocation = -1
    private var timeLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        amplitudeLocation = GLES20.glGetUniformLocation(quad.programId, "uAmplitude")
        frequencyLocation = GLES20.glGetUniformLocation(quad.programId, "uFrequency")
        seedLocation = GLES20.glGetUniformLocation(quad.programId, "uSeed")
        timeLocation = GLES20.glGetUniformLocation(quad.programId, "uTime")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        amplitude: Float,
        frequency: Float,
        seed: Float,
        timeSeconds: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(amplitudeLocation, amplitude)
        GLES20.glUniform1f(frequencyLocation, frequency)
        GLES20.glUniform1f(seedLocation, seed)
        GLES20.glUniform1f(timeLocation, timeSeconds)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uAmplitude;
            uniform float uFrequency;
            uniform float uSeed;
            uniform float uTime;
            uniform int uMode;
            void main() {
                float t = uTime * uFrequency * 6.2831853;
                vec2 offset;
                if (uMode == 0) {
                    offset = vec2(
                        sin(t + uSeed) * uAmplitude,
                        cos(t + uSeed * 1.7) * uAmplitude
                    );
                } else {
                    offset = vec2(
                        sin(t + uSeed) * uAmplitude + sin(t * 2.3 + uSeed) * uAmplitude * 0.4,
                        cos(t * 1.7 + uSeed) * uAmplitude + cos(t * 3.1 + uSeed) * uAmplitude * 0.3
                    );
                }
                gl_FragColor = texture2D(uTexture, clamp(vUv + offset, 0.0, 1.0));
            }
        """
    }
}

class OpticsKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var lengthLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        lengthLocation = GLES20.glGetUniformLocation(quad.programId, "uLength")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && lengthLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        length: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(lengthLocation, length)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uLength;
            uniform int uMode;
            void main() {
                vec2 centered = vUv - 0.5;
                if (uMode == 2) {
                    vec2 dir = centered * uLength * 4.0;
                    float r = texture2D(uTexture, vUv + dir).r;
                    float g = texture2D(uTexture, vUv).g;
                    float b = texture2D(uTexture, vUv - dir).b;
                    gl_FragColor = vec4(r, g, b, 1.0);
                } else if (uMode == 1) {
                    vec3 sum = vec3(0.0);
                    for (int i = -4; i <= 4; i++) {
                        sum += texture2D(uTexture, vUv + vec2(float(i) * uLength * 0.25, 0.0)).rgb;
                    }
                    gl_FragColor = vec4(sum / 9.0, 1.0);
                } else {
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < 8; i++) {
                        float t = float(i) / 8.0;
                        sum += texture2D(uTexture, mix(vUv, vec2(0.5), t * uLength * 4.0)).rgb;
                    }
                    gl_FragColor = vec4(sum / 8.0, 1.0);
                }
            }
        """
    }
}

class LayerStyleKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var radiusLocation = -1
    private var opacityLocation = -1
    private var modeLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        radiusLocation = GLES20.glGetUniformLocation(quad.programId, "uRadius")
        opacityLocation = GLES20.glGetUniformLocation(quad.programId, "uOpacity")
        modeLocation = GLES20.glGetUniformLocation(quad.programId, "uMode")
        return textureLocation >= 0 && radiusLocation >= 0 && opacityLocation >= 0 && modeLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        radius: Float,
        opacity: Float,
        mode: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniform1f(radiusLocation, radius)
        GLES20.glUniform1f(opacityLocation, opacity)
        GLES20.glUniform1i(modeLocation, mode)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uRadius;
            uniform float uOpacity;
            uniform int uMode;
            void main() {
                vec4 color = texture2D(uTexture, vUv);
                float step = max(uRadius * 0.25, 0.001);
                if (uMode == 0) {
                    vec4 shadow = texture2D(uTexture, vUv - vec2(step, step));
                    gl_FragColor = vec4(mix(color.rgb, vec3(0.0), shadow.a * uOpacity), 1.0);
                } else if (uMode == 1) {
                    float edge = 0.0;
                    edge += texture2D(uTexture, vUv + vec2(step, 0.0)).a;
                    edge += texture2D(uTexture, vUv - vec2(step, 0.0)).a;
                    edge += texture2D(uTexture, vUv + vec2(0.0, step)).a;
                    edge += texture2D(uTexture, vUv - vec2(0.0, step)).a;
                    edge = clamp(edge, 0.0, 1.0);
                    gl_FragColor = vec4(mix(color.rgb, vec3(1.0), edge * uOpacity), 1.0);
                } else if (uMode == 2) {
                    float dx = texture2D(uTexture, vUv + vec2(step, 0.0)).r -
                        texture2D(uTexture, vUv - vec2(step, 0.0)).r;
                    float dy = texture2D(uTexture, vUv + vec2(0.0, step)).r -
                        texture2D(uTexture, vUv - vec2(0.0, step)).r;
                    float light = clamp(0.5 + (dx + dy) * 2.0, 0.0, 1.0);
                    gl_FragColor = vec4(color.rgb * (0.7 + light * 0.6), 1.0);
                } else {
                    float dx = texture2D(uTexture, vUv + vec2(step, 0.0)).r -
                        texture2D(uTexture, vUv - vec2(step, 0.0)).r;
                    float dy = texture2D(uTexture, vUv + vec2(0.0, step)).r -
                        texture2D(uTexture, vUv - vec2(0.0, step)).r;
                    float v = clamp(0.5 + (dx + dy) * 3.0, 0.0, 1.0);
                    gl_FragColor = vec4(vec3(v), 1.0);
                }
            }
        """
    }
}

class CornerPinKernel {
    private val quad = FullscreenQuad()
    private var textureLocation = -1
    private var matrixLocation = -1

    fun initialize(): Boolean {
        if (!quad.create(FRAGMENT_SHADER)) return false
        textureLocation = GLES20.glGetUniformLocation(quad.programId, "uTexture")
        matrixLocation = GLES20.glGetUniformLocation(quad.programId, "uInvH")
        return textureLocation >= 0 && matrixLocation >= 0
    }

    fun render(
        inputTexture: Int,
        outputFramebuffer: Int,
        width: Int,
        height: Int,
        inverse: FloatArray?,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        quad.use(0f, 0f, 1f, 1f)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniformMatrix3fv(matrixLocation, 1, false, inverse ?: IDENTITY, 0)
        quad.draw()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() = quad.release()

    companion object {
        private val IDENTITY =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                1f,
            )

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform mat3 uInvH;
            void main() {
                vec3 p = uInvH * vec3(vUv, 1.0);
                vec2 src = p.xy / p.z;
                if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, src);
                }
            }
        """
    }
}
