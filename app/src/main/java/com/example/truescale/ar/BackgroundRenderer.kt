/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.truescale.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Renders the AR background camera image. */
class BackgroundRenderer {
    companion object {
        private val TAG = BackgroundRenderer::class.java.simpleName

        private const val COORDS_PER_VERTEX = 2
        private const val TEXCOORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        // Simplified quad vertex coordinates
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f,
        )
    }

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    private var quadProgram = 0
    private var isInitialized = false

    private var quadPositionHandle = 0
    private var quadTexCoordHandle = 0
    private var textureUniformHandle = 0
    var textureId = -1
        private set

    // Simple vertex shader
    private val VERTEX_SHADER_CODE = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
           gl_Position = a_Position;
           v_TexCoord = a_TexCoord;
        }
        """.trimIndent()

    // Simple fragment shader for external texture
    private val FRAGMENT_SHADER_CODE = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, v_TexCoord);
        }
        """.trimIndent()

    /**
     * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
     * the OpenGL thread, typically in [GLSurfaceView.Renderer.onSurfaceCreated].
     */
    fun createOnGlThread() {
        if (isInitialized) {
            Log.w(TAG, "BackgroundRenderer already initialized")
            return
        }

        try {
            // Generate the texture ID.
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            if (textureId == 0) {
                Log.e(TAG, "Failed to generate texture ID")
                return
            }

            val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(textureTarget, textureId)
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            // Initialize vertex coordinates buffer
            val numVertices = QUAD_COORDS.size / COORDS_PER_VERTEX
            val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
            bbCoords.order(ByteOrder.nativeOrder())
            quadCoords = bbCoords.asFloatBuffer()
            quadCoords.put(QUAD_COORDS)
            quadCoords.position(0)

            // Initialize texture coordinates buffer
            val bbTexCoords = ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE)
            bbTexCoords.order(ByteOrder.nativeOrder())
            quadTexCoords = bbTexCoords.asFloatBuffer()
            // Default texture coordinates (will be updated by ARCore)
            quadTexCoords.put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
            quadTexCoords.position(0)

            // Create shader program
            quadProgram = createShaderProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE)
            if (quadProgram == 0) {
                Log.e(TAG, "Failed to create shader program for background")
                cleanup()
                return
            }

            // Get shader attribute/uniform locations
            quadPositionHandle = GLES20.glGetAttribLocation(quadProgram, "a_Position")
            quadTexCoordHandle = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
            textureUniformHandle = GLES20.glGetUniformLocation(quadProgram, "sTexture")

            if (quadPositionHandle == -1 || quadTexCoordHandle == -1 || textureUniformHandle == -1) {
                Log.e(TAG, "Failed to get shader attribute/uniform locations")
                cleanup()
                return
            }

            checkGlError("BackgroundRenderer.createOnGlThread")
            isInitialized = true
            Log.d(TAG, "BackgroundRenderer created successfully. Texture ID: $textureId")

        } catch (e: Exception) {
            Log.e(TAG, "Error during BackgroundRenderer initialization", e)
            cleanup()
        }
    }

    /**
     * Draws the AR background image. The image will be drawn such that virtual content rendered with
     * the matrices provided by [Frame.getCamera] will accurately track
     * imperfections in the camera image distortion. Must be called on the OpenGL thread, typically in
     * [GLSurfaceView.Renderer.onDrawFrame].
     *
     * @param frame The current AR frame, which contains the camera image and transformation matrices.
     */
    fun draw(frame: Frame) {
        if (!isInitialized) {
            Log.w(TAG, "BackgroundRenderer not initialized, skipping draw")
            return
        }

        if (textureId == -1) {
            Log.e(TAG, "Texture not initialized")
            return
        }

        try {
            // Update texture coordinates if display geometry has changed
            if (frame.hasDisplayGeometryChanged()) {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadCoords,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    quadTexCoords
                )
            }

            // Disable depth testing for background rendering
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)

            // Bind texture and use shader program
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUseProgram(quadProgram)

            // Set vertex positions
            GLES20.glVertexAttribPointer(
                quadPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords)

            // Set texture coordinates
            GLES20.glVertexAttribPointer(
                quadTexCoordHandle, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords)

            // Set active texture unit and bind texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureUniformHandle, 0)

            // Enable vertex arrays
            GLES20.glEnableVertexAttribArray(quadPositionHandle)
            GLES20.glEnableVertexAttribArray(quadTexCoordHandle)

            // Draw the quad
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Disable vertex arrays
            GLES20.glDisableVertexAttribArray(quadPositionHandle)
            GLES20.glDisableVertexAttribArray(quadTexCoordHandle)

            // Restore depth state for subsequent drawing
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            // Unbind resources
            GLES20.glUseProgram(0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            checkGlError("BackgroundRenderer.draw")

        } catch (e: Exception) {
            Log.e(TAG, "Error during background rendering", e)
        }
    }

    /**
     * Clean up OpenGL resources. Should be called when the renderer is no longer needed.
     */
    fun cleanup() {
        if (quadProgram != 0) {
            GLES20.glDeleteProgram(quadProgram)
            quadProgram = 0
        }

        if (textureId != -1) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = -1
        }

        isInitialized = false
        Log.d(TAG, "BackgroundRenderer cleaned up")
    }

    /**
     * Check if the renderer is properly initialized.
     */
    fun isReady(): Boolean = isInitialized && textureId != -1

    // --- Shader Compilation Helpers ---
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader != 0) {
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader $type: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
        } else {
            Log.e(TAG, "Could not create shader $type")
            return 0
        }
        return shader
    }

    private fun createShaderProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        if (vertexShader == 0) return 0

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            checkGlError("glAttachShader vertex")
            GLES20.glAttachShader(program, fragmentShader)
            checkGlError("glAttachShader fragment")
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                return 0
            }
        } else {
            Log.e(TAG, "Could not create program")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }

        // Clean up shaders after linking
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun checkGlError(operation: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$operation: glError 0x${Integer.toHexString(error)}")
        }
    }
}