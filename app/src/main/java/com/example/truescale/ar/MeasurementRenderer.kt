package com.example.truescale.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.truescale.ar.BackgroundRenderer
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.example.truescale.utils.Vector3
import com.example.truescale.ar.ArMeasurementManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MeasurementRenderer(
    private val context: Context,
    private val arManager: ArMeasurementManager,
    private val onFrame: () -> Unit
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

    private var backgroundRenderer: BackgroundRenderer? = null
    private var planeRenderer: PlaneRenderer? = null

    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile private var measurementLines = listOf<Line>()
    @Volatile private var measurementPoints = listOf<Point>()
    @Volatile private var previewLine: Line? = null

    data class Line(
        val start: Vector3,
        val end: Vector3,
        val color: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f),
        val thickness: Float = 5.0f
    ) {
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
        val color: FloatArray = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f),
        val size: Float = 20.0f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Point
            return position == other.position
        }
        override fun hashCode(): Int = position.hashCode()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread()
            
            if (backgroundRenderer?.isReady() != true) {
                Log.e(TAG, "Failed to initialize BackgroundRenderer")
                return
            }

            planeRenderer = PlaneRenderer()
            planeRenderer?.createOnGlThread()

            shaderProgram = createShaderProgram()
            if (shaderProgram == 0) {
                Log.e(TAG, "Failed to create shader program")
                return
            }

            positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
            colorHandle = GLES20.glGetUniformLocation(shaderProgram, "vColor")
            mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")

            checkGlError("onSurfaceCreated setup")

            Log.d(TAG, "Surface created, OpenGL initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onSurfaceCreated", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        try {
            val frame = arManager.update() ?: return
            val camera = frame.camera

            backgroundRenderer?.draw(frame)
            checkGlError("draw background")

            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }

            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            val planes = arManager.getDetectedPlanes()
            planeRenderer?.drawPlanes(planes, camera.displayOrientedPose, projectionMatrix, viewMatrix)
            checkGlError("draw planes")

            renderMeasurements()
            checkGlError("render measurements")

            onFrame()

        } catch (e: Exception) {
            Log.e(TAG, "Error during onDrawFrame", e)
        }
    }

    private fun renderMeasurements() {
        val linesToRender = measurementLines
        val pointsToRender = measurementPoints

        if (linesToRender.isEmpty() && pointsToRender.isEmpty() && previewLine == null) return

        GLES20.glUseProgram(shaderProgram)

        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        if (linesToRender.isNotEmpty()) {
            GLES20.glLineWidth(linesToRender.first().thickness)
            GLES20.glEnableVertexAttribArray(positionHandle)

            linesToRender.forEach { line ->
                Matrix.setIdentityM(modelMatrix, 0)
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

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

        previewLine?.let { line ->
            GLES20.glLineWidth(line.thickness)
            GLES20.glEnableVertexAttribArray(positionHandle)

            Matrix.setIdentityM(modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

            val vertices = floatArrayOf(
                line.start.x, line.start.y, line.start.z,
                line.end.x, line.end.y, line.end.z
            )
            val vertexBuffer = createFloatBuffer(vertices)

            GLES20.glUniform4fv(colorHandle, 1, line.color, 0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)

            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        if (pointsToRender.isNotEmpty()) {
            GLES20.glEnableVertexAttribArray(positionHandle)

            pointsToRender.forEach { point ->
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

                val vertices = floatArrayOf(point.position.x, point.position.y, point.position.z)
                val vertexBuffer = createFloatBuffer(vertices)

                GLES20.glUniform4fv(colorHandle, 1, point.color, 0)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        GLES20.glUseProgram(0)
    }

    private fun drawLine(line: Line, vpMatrix: FloatArray) {
        GLES20.glUseProgram(shaderProgram)
        GLES20.glEnableVertexAttribArray(positionHandle)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
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

    private fun drawPoint(point: Point, vpMatrix: FloatArray) {
        GLES20.glUseProgram(shaderProgram)
        GLES20.glEnableVertexAttribArray(positionHandle)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        val vertices = floatArrayOf(point.position.x, point.position.y, point.position.z)
        val vertexBuffer = createFloatBuffer(vertices)

        GLES20.glUniform4fv(colorHandle, 1, point.color, 0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glUseProgram(0)
    }

    fun addMeasurementLine(start: Vector3, end: Vector3, confidence: Float = 1.0f) {
        val color = when {
            confidence > 0.8f -> floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)
            confidence > 0.5f -> floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f)
            else -> floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)
        }
        val newLine = Line(start, end, color)
        val startPoint = Point(start)
        val endPoint = Point(end)

        synchronized(this) {
            measurementLines = measurementLines + newLine
            measurementPoints = (measurementPoints + startPoint + endPoint).distinct()
        }
    }

    fun updatePreviewLine(start: Vector3, end: Vector3) {
        previewLine = Line(start, end, color = floatArrayOf(1.0f, 1.0f, 1.0f, 0.7f))
    }

    fun clearMeasurements() {
        synchronized(this) {
            measurementLines = emptyList()
            measurementPoints = emptyList()
            previewLine = null
        }
    }

    fun getBackgroundRenderer(): BackgroundRenderer? = backgroundRenderer

    fun cleanup() {
        backgroundRenderer?.cleanup()
        backgroundRenderer = null
        
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        
        Log.d(TAG, "MeasurementRenderer cleaned up")
    }

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
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(array)
            .also { it.position(0) }
    }

    private fun checkGlError(operation: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$operation: glError 0x${Integer.toHexString(error)}")
        }
    }

}

class PlaneRenderer {
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

    private val planeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 0.5f)

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
        cameraPose: Pose,
        projectionMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (planeShaderProgram == 0) return

        GLES20.glUseProgram(planeShaderProgram)
        GLES20.glUniform4fv(planeColorHandle, 1, planeColor, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        planes.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                val planeModelMatrix = FloatArray(16)
                plane.centerPose.toMatrix(planeModelMatrix, 0)

                val mvpMatrix = FloatArray(16)
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, planeModelMatrix, 0)
                GLES20.glUniformMatrix4fv(planeMvpMatrixHandle, 1, false, mvpMatrix, 0)

                val vertexBuffer = planeBuffers.getOrPut(plane) {
                    val polygon = plane.polygon
                    val directBuffer = ByteBuffer.allocateDirect(polygon.remaining() * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    directBuffer.put(polygon.duplicate())
                    directBuffer.position(0)
                    directBuffer
                }

                GLES20.glEnableVertexAttribArray(planePositionHandle)
                GLES20.glVertexAttribPointer(planePositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexBuffer.capacity() / 3)
                GLES20.glDisableVertexAttribArray(planePositionHandle)
            }
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0)

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
}
