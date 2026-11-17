/*
 * Kinetix3D Studio — engine-render
 * Phase 2 kernels: pass-through, ColorSuite and separable blur.
 */

package com.kinetix3d.render

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
