// File: MeasurementRenderer.kt
package com.example.truescale.ar // Correct package

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.truescale.ar.BackgroundRenderer // Local BackgroundRenderer
import com.google.ar.core.Coordinates2d    // External ARCore import
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session // If needed directly, though likely handled by ArManager
import com.google.ar.core.TrackingState
// Add any other specific ARCore classes used directly in this file if needed
// --- CORRECTED IMPORT ---
import com.example.truescale.utils.Vector3
import com.example.truescale.ar.ArMeasurementManager
// -------------------------
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MeasurementRenderer(
    private val context: Context,
    private val arManager: ArMeasurementManager // Uses correct ArMeasurementManager import
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MeasurementRenderer"
        // Shaders remain the same
        private const val VERTEX_SHADER = """
            attribute vec4 vPosition;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                gl_PointSize = 20.0; // Point size for measurement points
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

    // Use the correctly imported BackgroundRenderer
    private var backgroundRenderer: BackgroundRenderer? = null
    // Keep the local PlaneRenderer for now
    private var planeRenderer: PlaneRenderer? = null

    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16) // For potential object transformations
    private val mvpMatrix = FloatArray(16)    // Model-View-Projection matrix

    // Thread-safe lists for measurement visualization
    @Volatile private var measurementLines = listOf<Line>()
    @Volatile private var measurementPoints = listOf<Point>()


    // Data classes for lines and points (unchanged)
    data class Line(
        val start: Vector3,
        val end: Vector3,
        val color: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f), // Default green
        val thickness: Float = 5.0f // Slightly thinner lines
    ) {
        // Add equals/hashCode for potential future optimizations if needed
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Line
            if (start != other.start) return false
            if (end != other.end) return false
            return true
        }
        override fun hashCode(): Int {
            var result = start.hashCode()
            result = 31 * result + end.hashCode()
            return result
        }
    }


    data class Point(
        val position: Vector3,
        val color: FloatArray = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f), // Default yellow
        val size: Float = 20.0f // Make points slightly larger
    ) {
        // Add equals/hashCode
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Point
            return position == other.position
        }
        override fun hashCode(): Int = position.hashCode()
    }


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f) // Dark grey background

        try {
            // Initialize ARCore's BackgroundRenderer
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread()
            
            if (backgroundRenderer?.isReady() != true) {
                Log.e(TAG, "Failed to initialize BackgroundRenderer")
                return
            }

            // Initialize custom PlaneRenderer
            planeRenderer = PlaneRenderer()
            planeRenderer?.createOnGlThread()

            // Create shader program for lines/points
            shaderProgram = createShaderProgram()
            if (shaderProgram == 0) {
                Log.e(TAG, "Failed to create shader program")
                return
            }

            // Get shader attribute/uniform locations
            positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
            colorHandle = GLES20.glGetUniformLocation(shaderProgram, "vColor")
            mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")

            // Check for GL errors
            checkGlError("onSurfaceCreated setup")

            Log.d(TAG, "Surface created, OpenGL initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onSurfaceCreated", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // ARCore display geometry is now handled by MeasurementFragment's DisplayListener
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        try {
            // Obtain the current frame from ARCore session each frame
            val frame = arManager.update() ?: return
            val camera = frame.camera

            // Render camera background first
            backgroundRenderer?.draw(frame)
            checkGlError("draw background")


            // Don't render further if tracking is paused
            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }

            // Get camera matrices
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // Render detected planes (optional, for visualization)
            // GLES20.glEnable(GLES20.GL_BLEND) // Optional blending for planes
            // GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val planes = arManager.getDetectedPlanes()
            planeRenderer?.drawPlanes(planes, camera.displayOrientedPose, projectionMatrix, viewMatrix)
            checkGlError("draw planes")
            // GLES20.glDisable(GLES20.GL_BLEND)


            // Render measurement lines and points
            renderMeasurements()
            checkGlError("render measurements")


        } catch (e: Exception) {
            Log.e(TAG, "Error during onDrawFrame", e)
        }
    }

    private fun renderMeasurements() {
        // Get immutable copies for rendering to avoid concurrency issues
        val linesToRender = measurementLines
        val pointsToRender = measurementPoints


        if (linesToRender.isEmpty() && pointsToRender.isEmpty()) return

        GLES20.glUseProgram(shaderProgram)

        // Calculate combined View-Projection matrix once
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0) // VP = P * V


        // --- Render Lines ---
        if (linesToRender.isNotEmpty()) {
            GLES20.glLineWidth(linesToRender.first().thickness) // Set line width (assuming all lines have same thickness for now)
            GLES20.glEnableVertexAttribArray(positionHandle)

            linesToRender.forEach { line ->
                // Simple Model matrix (identity for world space lines)
                Matrix.setIdentityM(modelMatrix, 0)
                // Calculate full MVP matrix: MVP = P * V * M (where M is identity here)
                // Since M is identity, MVP = VP
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0) // Use VP matrix

                val vertices = floatArrayOf(
                    line.start.x, line.start.y, line.start.z,
                    line.end.x, line.end.y, line.end.z
                )
                val vertexBuffer = createFloatBuffer(vertices)

                GLES20.glUniform4fv(colorHandle, 1, line.color, 0)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
            }
            GLES20.glDisableVertexAttribArray(positionHandle)
        }


        // --- Render Points ---
        if (pointsToRender.isNotEmpty()) {
            // Points need GL_POINT_SIZE enabled in shader (which VERTEX_SHADER does)
            GLES20.glEnableVertexAttribArray(positionHandle)

            pointsToRender.forEach { point ->
                // MVP matrix is the same (VP) as for lines since points are in world space
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0) // Use VP matrix

                val vertices = floatArrayOf(point.position.x, point.position.y, point.position.z)
                val vertexBuffer = createFloatBuffer(vertices)

                GLES20.glUniform4fv(colorHandle, 1, point.color, 0)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
            GLES20.glDisableVertexAttribArray(positionHandle)
        }


        GLES20.glUseProgram(0) // Unbind shader program
    }

    // Helper to draw a single line (kept for potential direct use, but renderMeasurements is primary)
    private fun drawLine(line: Line, vpMatrix: FloatArray) {
        GLES20.glUseProgram(shaderProgram)
        GLES20.glEnableVertexAttribArray(positionHandle)

        Matrix.setIdentityM(modelMatrix, 0) // Assuming lines are in world space
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0) // MVP = VP * M (identity)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)


        val vertices = floatArrayOf(line.start.x, line.start.y, line.start.z, line.end.x, line.end.y, line.end.z)
        val vertexBuffer = createFloatBuffer(vertices)

        GLES20.glUniform4fv(colorHandle, 1, line.color, 0)
        GLES20.glLineWidth(line.thickness)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glUseProgram(0)
    }

    // Helper to draw a single point (kept for potential direct use)
    private fun drawPoint(point: Point, vpMatrix: FloatArray) {
        GLES20.glUseProgram(shaderProgram)
        GLES20.glEnableVertexAttribArray(positionHandle)

        Matrix.setIdentityM(modelMatrix, 0) // Assuming points are in world space
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0) // MVP = VP * M (identity)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)


        val vertices = floatArrayOf(point.position.x, point.position.y, point.position.z)
        val vertexBuffer = createFloatBuffer(vertices)

        GLES20.glUniform4fv(colorHandle, 1, point.color, 0)
        // Point size is set in the vertex shader: gl_PointSize = 20.0;
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glUseProgram(0)
    }


    // --- Update methods should modify the volatile lists safely ---
    fun addMeasurementLine(start: Vector3, end: Vector3, confidence: Float = 1.0f) {
        val color = when {
            confidence > 0.8f -> floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Green
            confidence > 0.5f -> floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f) // Yellow
            else -> floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f) // Red
        }
        val newLine = Line(start, end, color)
        val startPoint = Point(start) // Use default color/size
        val endPoint = Point(end)

        // Atomically update lists
        synchronized(this) {
            measurementLines = measurementLines + newLine
            measurementPoints = (measurementPoints + startPoint + endPoint).distinct() // Avoid duplicate points
        }
    }


    fun clearMeasurements() {
        synchronized(this) {
            measurementLines = emptyList()
            measurementPoints = emptyList()
        }
    }

    /**
     * Get the background renderer instance for camera texture setup
     */
    fun getBackgroundRenderer(): BackgroundRenderer? = backgroundRenderer

    /**
     * Clean up OpenGL resources. Should be called when the renderer is no longer needed.
     */
    fun cleanup() {
        backgroundRenderer?.cleanup()
        backgroundRenderer = null
        
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        
        Log.d(TAG, "MeasurementRenderer cleaned up")
    }
    // -----------------------------------------------------------


    private fun createShaderProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragmentShader == 0) return 0

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
                return 0
            }
        } else {
            Log.e(TAG, "Could not create program")
            return 0
        }
        return program
    }

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

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4) // Float is 4 bytes
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(array)
            .also { it.position(0) } // Reset position after putting data
    }

    // Helper function to check for OpenGL errors
    private fun checkGlError(operation: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$operation: glError 0x${Integer.toHexString(error)}")
            // Consider throwing an exception in debug builds
            // throw RuntimeException("$operation: glError ${error.toHexString()}")
        }
    }

}


// Local PlaneRenderer class (Keep for now, can be improved later)
class PlaneRenderer {
    // Basic shader for simple plane visualization (e.g., colored polygon)
    private val PLANE_VERTEX_SHADER = """
        attribute vec4 a_Position;
        uniform mat4 u_MvpMatrix;
        void main() {
            gl_Position = u_MvpMatrix * a_Position;
        }
    """
    private val PLANE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 u_Color;
        void main() {
            gl_FragColor = u_Color;
        }
    """
    private var planeShaderProgram: Int = 0
    private var planePositionHandle: Int = 0
    private var planeMvpMatrixHandle: Int = 0
    private var planeColorHandle: Int = 0

    private val planeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 0.5f) // White, semi-transparent

    // Keep track of plane buffers
    private val planeBuffers = mutableMapOf<Plane, FloatBuffer>()


    fun createOnGlThread() {
        planeShaderProgram = createPlaneShaderProgram()
        if (planeShaderProgram == 0) {
            Log.e("PlaneRenderer", "Failed to create plane shader program")
            return
        }
        planePositionHandle = GLES20.glGetAttribLocation(planeShaderProgram, "a_Position")
        planeMvpMatrixHandle = GLES20.glGetUniformLocation(planeShaderProgram, "u_MvpMatrix")
        planeColorHandle = GLES20.glGetUniformLocation(planeShaderProgram, "u_Color")
        Log.d("PlaneRenderer", "Plane renderer initialized")
    }

    fun drawPlanes(
        planes: Collection<Plane>,
        cameraPose: Pose, // Camera pose needed for proper rendering
        projectionMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (planeShaderProgram == 0) return

        GLES20.glUseProgram(planeShaderProgram)
        GLES20.glUniform4fv(planeColorHandle, 1, planeColor, 0)

        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Optional: Disable depth writing for transparent planes
        GLES20.glDepthMask(false)


        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        planes.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) { // Render only top-level planes
                val planeModelMatrix = FloatArray(16)
                plane.centerPose.toMatrix(planeModelMatrix, 0) // Get plane's transform

                val mvpMatrix = FloatArray(16)
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, planeModelMatrix, 0) // MVP = VP * M
                GLES20.glUniformMatrix4fv(planeMvpMatrixHandle, 1, false, mvpMatrix, 0)


                // Get or create vertex buffer for this plane's polygon
                val vertexBuffer = planeBuffers.getOrPut(plane) {
                    val polygon = plane.polygon // This is a FloatBuffer directly
                    // Make a copy if you need to modify or ensure it's direct
                    val directBuffer = ByteBuffer.allocateDirect(polygon.remaining() * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    directBuffer.put(polygon.duplicate()) // Use duplicate to avoid changing original position
                    directBuffer.position(0)
                    directBuffer
                }

                // Render the plane polygon
                GLES20.glEnableVertexAttribArray(planePositionHandle)
                // Assuming polygon buffer contains (x, y, z) triplets
                GLES20.glVertexAttribPointer(planePositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                // Draw triangles using GL_TRIANGLE_FAN (assuming polygon center is first vertex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexBuffer.capacity() / 3) // numVertices = capacity / components_per_vertex
                GLES20.glDisableVertexAttribArray(planePositionHandle)
            }
        }

        // Restore GL state
        GLES20.glDepthMask(true) // Re-enable depth writing
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0) // Unbind shader

        // Clean up buffers for planes that are no longer tracked
        planeBuffers.keys.retainAll { it.trackingState == TrackingState.TRACKING }
    }

    private fun createPlaneShaderProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, PLANE_VERTEX_SHADER)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, PLANE_FRAGMENT_SHADER)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e("PlaneRenderer", "Could not link program: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return 0
            }
        } else {
            Log.e("PlaneRenderer", "Could not create program")
            return 0
        }
        return program
    }

    // --- MUST MATCH SIGNATURE IN MeasurementRenderer ---
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader != 0) {
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("PlaneRenderer", "Could not compile shader $type: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
        } else {
            Log.e("PlaneRenderer", "Could not create shader $type")
            return 0
        }
        return shader
    }
    // --- END ---
}

