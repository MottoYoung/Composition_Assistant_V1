package com.photoframer.arcore

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

enum class CameraPoseSource {
    ARCORE,
    DEVICE_MOTION
}

data class CameraPoseSample(
    val source: CameraPoseSource,
    val isTracking: Boolean,
    val lateralMeters: Float,
    val verticalMeters: Float,
    val forwardMeters: Float,
    val yawDeltaDegrees: Float,
    val pitchDeltaDegrees: Float,
    val rollDeltaDegrees: Float,
    val confidence: Float
)

/**
 * ARCore-first 的机位追踪器。
 *
 * 当前相机主链路仍由 CameraX 驱动，因此这里采用“尽力接入”策略：
 * - ARCore 可用且能拿到位姿时，提供真实平移增量
 * - 否则退回到设备姿态变化，至少避免引导完全失明
 */
class ArCorePoseTracker(
    context: Context
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val motionSensorsRegistered = AtomicBoolean(false)
    private val trackingGeneration = AtomicInteger(0)
    private val latestSample = AtomicReference<CameraPoseSample?>()
    private val orientationLock = Any()

    @Volatile
    private var arCoreFuture: Future<*>? = null

    @Volatile
    private var shouldAttemptArCore: Boolean = false

    @Volatile
    private var viewportWidth: Int = 0

    @Volatile
    private var viewportHeight: Int = 0

    @Volatile
    private var displayRotation: Int = 0

    private var baselineOrientation: FloatArray? = null
    private var latestOrientationDelta = floatArrayOf(0f, 0f, 0f)

    fun start(
        activity: Activity?,
        preferArCore: Boolean,
        viewportWidth: Int,
        viewportHeight: Int,
        displayRotation: Int,
        resetBaseline: Boolean = false
    ) {
        updateViewport(viewportWidth, viewportHeight, displayRotation)
        if (resetBaseline || !running.get()) {
            resetPoseBaseline()
        }
        registerMotionSensors()
        running.set(true)
        shouldAttemptArCore = preferArCore && activity != null

        val currentFuture = arCoreFuture
        if (!shouldAttemptArCore && currentFuture != null) {
            currentFuture.cancel(true)
            arCoreFuture = null
        }

        if (shouldAttemptArCore && arCoreFuture == null) {
            val generation = trackingGeneration.incrementAndGet()
            arCoreFuture = executor.submit {
                runArCoreLoop(activity!!, generation)
            }
        } else if (!shouldAttemptArCore) {
            publishMotionOnlySample()
        }
    }

    fun stop() {
        running.set(false)
        shouldAttemptArCore = false
        trackingGeneration.incrementAndGet()
        arCoreFuture?.cancel(true)
        arCoreFuture = null
        unregisterMotionSensors()
        resetPoseBaseline()
        latestSample.set(null)
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    fun updateViewport(
        width: Int,
        height: Int,
        rotation: Int
    ) {
        viewportWidth = width
        viewportHeight = height
        displayRotation = rotation
    }

    fun latestPoseSample(): CameraPoseSample? = latestSample.get()

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        synchronized(orientationLock) {
            if (baselineOrientation == null) {
                baselineOrientation = orientation.copyOf()
                latestOrientationDelta = floatArrayOf(0f, 0f, 0f)
            } else {
                val baseline = baselineOrientation ?: orientation
                latestOrientationDelta = floatArrayOf(
                    normalizeDeltaDegrees(orientation[0] - baseline[0]),
                    normalizeDeltaDegrees(orientation[1] - baseline[1]),
                    normalizeDeltaDegrees(orientation[2] - baseline[2])
                )
            }
        }

        if (!shouldAttemptArCore) {
            publishMotionOnlySample()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetPoseBaseline() {
        synchronized(orientationLock) {
            baselineOrientation = null
            latestOrientationDelta = floatArrayOf(0f, 0f, 0f)
        }
        latestSample.set(null)
    }

    private fun registerMotionSensors() {
        rotationVectorSensor?.let {
            if (motionSensorsRegistered.compareAndSet(false, true)) {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun unregisterMotionSensors() {
        if (motionSensorsRegistered.compareAndSet(true, false)) {
            sensorManager.unregisterListener(this)
        }
    }

    private fun runArCoreLoop(activity: Activity, generation: Int) {
        var session: Session? = null
        try {
            session = Session(activity, setOf(Session.Feature.SHARED_CAMERA))
            val config = Config(session).apply {
                setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
                setTextureUpdateMode(Config.TextureUpdateMode.EXPOSE_HARDWARE_BUFFER)
            }
            session.configure(config)
            applyDisplayGeometry(session)
            session.resume()

            var baselinePose: Pose? = null

            while (
                running.get() &&
                shouldAttemptArCore &&
                trackingGeneration.get() == generation &&
                !Thread.currentThread().isInterrupted
            ) {
                applyDisplayGeometry(session)
                val frame = session.update()
                val camera = frame.camera
                if (camera.trackingState == TrackingState.TRACKING) {
                    val currentPose = camera.pose
                    if (baselinePose == null) {
                        baselinePose = currentPose
                    }
                    val localDelta = computeBaselineLocalDelta(
                        baselinePose = baselinePose ?: currentPose,
                        currentPose = currentPose
                    )
                    publishArCoreSample(
                        lateralMeters = localDelta[0],
                        verticalMeters = localDelta[1],
                        // Camera local forward is -Z; expose forward as positive.
                        forwardMeters = -localDelta[2]
                    )
                } else {
                    publishMotionOnlySample(confidence = 0.42f)
                }

                Thread.sleep(30)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 当前主链路仍是 CameraX，部分设备/场景下 ARCore 共享相机可能拿不到稳定追踪。
            // 这里静默退回设备姿态辅助，避免打断用户流程。
            publishMotionOnlySample(confidence = 0.40f)
        } finally {
            try {
                session?.pause()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            if (trackingGeneration.get() == generation) {
                arCoreFuture = null
            }
        }
    }

    private fun computeBaselineLocalDelta(
        baselinePose: Pose,
        currentPose: Pose
    ): FloatArray {
        val baselineTranslation = baselinePose.translation
        val currentTranslation = currentPose.translation
        val worldDelta = floatArrayOf(
            currentTranslation[0] - baselineTranslation[0],
            currentTranslation[1] - baselineTranslation[1],
            currentTranslation[2] - baselineTranslation[2]
        )
        return rotateVectorByInverseQuaternion(
            vector = worldDelta,
            quaternion = baselinePose.rotationQuaternion
        )
    }

    private fun rotateVectorByInverseQuaternion(
        vector: FloatArray,
        quaternion: FloatArray
    ): FloatArray {
        val inverseQuaternion = floatArrayOf(
            -quaternion[0],
            -quaternion[1],
            -quaternion[2],
            quaternion[3]
        )
        return rotateVectorByQuaternion(vector, inverseQuaternion)
    }

    private fun rotateVectorByQuaternion(
        vector: FloatArray,
        quaternion: FloatArray
    ): FloatArray {
        val qx = quaternion[0]
        val qy = quaternion[1]
        val qz = quaternion[2]
        val qw = quaternion[3]
        val vx = vector[0]
        val vy = vector[1]
        val vz = vector[2]

        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)

        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        )
    }

    private fun applyDisplayGeometry(session: Session) {
        val width = viewportWidth
        val height = viewportHeight
        if (width > 0 && height > 0) {
            session.setDisplayGeometry(displayRotation, width, height)
        }
    }

    private fun publishArCoreSample(
        lateralMeters: Float,
        verticalMeters: Float,
        forwardMeters: Float
    ) {
        val orientationDelta = synchronized(orientationLock) { latestOrientationDelta.copyOf() }
        latestSample.set(
            CameraPoseSample(
                source = CameraPoseSource.ARCORE,
                isTracking = true,
                lateralMeters = lateralMeters,
                verticalMeters = verticalMeters,
                forwardMeters = forwardMeters,
                yawDeltaDegrees = orientationDelta[0],
                pitchDeltaDegrees = orientationDelta[1],
                rollDeltaDegrees = orientationDelta[2],
                confidence = 0.92f
            )
        )
    }

    private fun publishMotionOnlySample(confidence: Float = 0.52f) {
        val orientationDelta = synchronized(orientationLock) { latestOrientationDelta.copyOf() }
        val tiltMagnitude = maxOf(abs(orientationDelta[1]), abs(orientationDelta[2]))
        latestSample.set(
            CameraPoseSample(
                source = CameraPoseSource.DEVICE_MOTION,
                isTracking = tiltMagnitude > 1.2f,
                lateralMeters = 0f,
                verticalMeters = 0f,
                forwardMeters = 0f,
                yawDeltaDegrees = orientationDelta[0],
                pitchDeltaDegrees = orientationDelta[1],
                rollDeltaDegrees = orientationDelta[2],
                confidence = confidence
            )
        )
    }

    private fun normalizeDeltaDegrees(deltaRadians: Float): Float {
        var degrees = Math.toDegrees(deltaRadians.toDouble()).toFloat()
        while (degrees > 180f) degrees -= 360f
        while (degrees < -180f) degrees += 360f
        return degrees
    }
}
