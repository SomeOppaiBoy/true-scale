// File: MeasurementRenderer.kt
package com.truescale.app.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.*
import com.truescale.app.utils.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * MeasurementRenderer - Handles OpenGL rendering for AR measurements
 */
class MeasurementRenderer(
    private val context: Context,
    private val arManager: ArMeasurementManager
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MeasurementRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 vPosition;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                gl_PointSize = 20.0;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
    }

    // Rendering components
    private var backgroundRenderer: BackgroundRenderer? = null
    private var planeRenderer: PlaneRenderer? = null

    // Shader program for custom rendering
    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    // Matrices for 3D transformations
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Measurement visualization
    private val measurementLines = mutableListOf<Line>()
    private val measurementPoints = mutableListOf<Point>()

    data class Line(
        val start: Vector3,
        val end: Vector3,
        val color: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f),
        val thickness: Float = 8.0f
    )

    data class Point(
        val position: Vector3,
        val color: FloatArray = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f),
        val size: Float = 15.0f
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Initialize simplified renderers
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer?.createOnGlThread()

        planeRenderer = PlaneRenderer()
        planeRenderer?.createOnGlThread()

        // Create shader program for custom rendering
        shaderProgram = createShaderProgram()

        // Get shader handles
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
        colorHandle = GLES20.glGetUniformLocation(shaderProgram, "vColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")

        Log.d(TAG, "Surface created, OpenGL initialized")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        // Use encapsulated method instead of accessing session directly
        arManager.onSurfaceChanged(0, width, height)

        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame = arManager.update() ?: return
        val camera = frame.camera

        // Render camera background
        backgroundRenderer?.draw(frame)

        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // Get projection and view matrices
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // Enable depth testing
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        // Render detected planes
        val planes = arManager.getDetectedPlanes()
        planeRenderer?.drawPlanes(planes, camera.displayOrientedPose, projectionMatrix)

        // Render measurements
        renderMeasurements()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun renderMeasurements() {
        if (measurementLines.isEmpty() && measurementPoints.isEmpty()) return

        GLES20.glUseProgram(shaderProgram)

        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Calculate MVP matrix
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Render lines
        measurementLines.forEach { line ->
            drawLine(line)
        }

        // Render points
        measurementPoints.forEach { point ->
            drawPoint(point)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawLine(line: Line) {
        val vertices = floatArrayOf(
            line.start.x, line.start.y, line.start.z,
            line.end.x, line.end.y, line.end.z
        )

        val vertexBuffer = createFloatBuffer(vertices)

        GLES20.glUniform4fv(colorHandle, 1, line.color, 0)
        GLES20.glLineWidth(line.thickness)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawPoint(point: Point) {
        val vertices = floatArrayOf(point.position.x, point.position.y, point.position.z)
        val vertexBuffer = createFloatBuffer(vertices)

        GLES20.glUniform4fv(colorHandle, 1, point.color, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    fun addMeasurementLine(start: Vector3, end: Vector3, confidence: Float = 1.0f) {
        val color = when {
            confidence > 0.8f -> floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Green
            confidence > 0.5f -> floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f) // Yellow
            else -> floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f) // Red
        }

        measurementLines.add(Line(start, end, color))
        measurementPoints.add(Point(start, floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f)))
        measurementPoints.add(Point(end, floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f)))
    }

    fun clearMeasurements() {
        measurementLines.clear()
        measurementPoints.clear()
    }

    private fun createShaderProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(array)
        buffer.position(0)
        return buffer
    }
}

/**
 * Simplified BackgroundRenderer for camera feed
 */
class BackgroundRenderer {
    private var cameraTextureId = -1
    private var shaderProgram = -1

    fun createOnGlThread() {
        // Initialize camera texture rendering
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        Log.d("BackgroundRenderer", "Camera texture initialized")
    }

    fun draw(frame: Frame) {
        try {
            // Update camera texture
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
                Coordinates2d.TEXTURE_NORMALIZED,
                FloatArray(8)
            )
        } catch (e: Exception) {
            Log.e("BackgroundRenderer", "Error drawing camera background", e)
        }
    }
}

/**
 * Simplified PlaneRenderer
 */
class PlaneRenderer {
    fun createOnGlThread() {
        Log.d("PlaneRenderer", "Plane renderer initialized")
    }

    fun drawPlanes(planes: Collection<Plane>, cameraPose: Pose, projectionMatrix: FloatArray) {
        // Simple plane rendering - in production you'd render grid patterns
        planes.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING) {
                // Could render plane boundaries here
            }
        }
    }
}