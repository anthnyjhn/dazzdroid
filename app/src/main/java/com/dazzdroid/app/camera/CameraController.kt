package com.dazzdroid.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

data class CameraInfo(
    val id: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val hasFlash: Boolean,
    val rawSupported: Boolean,
    val manualSupported: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val evRange: Range<Int>,
    val evStep: Float,
    val maxZoom: Float,
    val previewSize: Size,
)

class CapturedPhoto(val jpeg: ByteArray, val dng: ByteArray?)

class CameraController(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("dazz-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    var info: CameraInfo? = null
        private set

    // Live parameters
    @Volatile private var evIndex = 0
    @Volatile private var manualIso: Int? = null
    @Volatile private var manualShutter: Long? = null
    @Volatile private var zoom = 1f
    @Volatile private var torch = false
    @Volatile private var afRegion: MeteringRectangle? = null
    @Volatile private var lastIso = 100
    @Volatile private var lastShutter = 10_000_000L
    private var openToken = 0

    fun cameraIds(): Pair<String?, String?> {
        var back: String? = null
        var front: String? = null
        for (id in manager.cameraIdList) {
            val f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (f == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id
            if (f == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id
        }
        return back to front
    }

    /**
     * Opens the camera and starts preview into [st]. [wide] chooses a 16:9 stream instead of 4:3.
     * [onOpened] runs on the main thread with the camera's info (or null on failure).
     */
    @SuppressLint("MissingPermission")
    fun open(front: Boolean, st: SurfaceTexture, wide: Boolean, raw: Boolean, onOpened: (CameraInfo?) -> Unit) {
        handler.post {
            closeInternal()
            val token = ++openToken
            val (backId, frontId) = try { cameraIds() } catch (e: Exception) { null to null }
            val id = (if (front) frontId ?: backId else backId ?: frontId)
            if (id == null) { main.post { onOpened(null) }; return@post }
            val c = manager.getCameraCharacteristics(id)
            chars = c
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val target = if (wide) 16f / 9f else 4f / 3f
            val preview = pick(map.getOutputSizes(SurfaceTexture::class.java), target, 1920 * 1440)
            val jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), target, Int.MAX_VALUE)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawOk = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && !rawSizes.isNullOrEmpty()
            val manualOk = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val inf = CameraInfo(
                id = id,
                front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                rawSupported = rawOk,
                manualSupported = manualOk,
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
                maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                previewSize = preview,
            )
            info = inf
            st.setDefaultBufferSize(preview.width, preview.height)
            val ps = Surface(st)
            previewSurface = ps
            val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpegReader = jr
            val rr = if (raw && rawOk) {
                val rs = rawSizes!!.maxByOrNull { it.width.toLong() * it.height }!!
                ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
            } else null
            rawReader = rr

            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        if (token != openToken) { cam.close(); return }
                        device = cam
                        val outputs = listOfNotNull(ps, jr.surface, rr?.surface)
                        try {
                            @Suppress("DEPRECATION")
                            cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (token != openToken) return
                                    session = s
                                    startRepeating()
                                    main.post { onOpened(inf) }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    main.post { onOpened(null) }
                                }
                            }, handler)
                        } catch (e: Exception) {
                            main.post { onOpened(null) }
                        }
                    }

                    override fun onDisconnected(cam: CameraDevice) { cam.close(); if (device == cam) device = null }
                    override fun onError(cam: CameraDevice, error: Int) {
                        cam.close(); if (device == cam) device = null
                        main.post { onOpened(null) }
                    }
                }, handler)
            } catch (e: Exception) {
                main.post { onOpened(null) }
            }
        }
    }

    private fun pick(sizes: Array<Size>?, aspect: Float, maxPixels: Int): Size {
        val all = sizes?.toList().orEmpty()
        val matching = all.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width * it.height <= maxPixels }
        return (matching.ifEmpty { all.filter { it.width * it.height <= maxPixels } }.ifEmpty { all })
            .maxByOrNull { it.width.toLong() * it.height } ?: Size(1440, 1080)
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        val c = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val iso = manualIso
        val sh = manualShutter
        if ((iso != null || sh != null) && info?.manualSupported == true) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso ?: lastIso)
            val exp = sh ?: lastShutter
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }
        b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val region = afRegion
        if (region != null && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        } else if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        cropRegion()?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
    }

    private fun cropRegion(): Rect? {
        val active = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val z = zoom.coerceIn(1f, info?.maxZoom ?: 1f)
        if (z <= 1.001f) return null
        val w = (active.width() / z).roundToInt()
        val h = (active.height() / z).roundToInt()
        val l = (active.width() - w) / 2
        val t = (active.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastShutter = it }
        }
    }

    private fun startRepeating() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (_: Exception) {
        }
    }

    fun setExposureCompensation(stops: Float) = handler.post {
        val inf = info ?: return@post
        evIndex = (stops / inf.evStep).roundToInt().coerceIn(inf.evRange.lower, inf.evRange.upper)
        startRepeating()
    }

    fun setManual(iso: Int?, shutterNs: Long?) = handler.post {
        manualIso = iso
        manualShutter = shutterNs
        startRepeating()
    }

    fun setZoom(z: Float) = handler.post {
        zoom = z
        startRepeating()
    }

    /** Tap to focus; x,y normalised to the upright viewfinder (0..1). */
    fun focusAt(nx: Float, ny: Float) = handler.post {
        val inf = info ?: return@post
        val active = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        // Convert the upright viewfinder point back to sensor coordinates.
        var sx = nx
        var sy = ny
        if (inf.front) sx = 1f - sx
        val (bx, by) = when (inf.sensorOrientation) {
            90 -> sy to 1f - sx
            180 -> 1f - sx to 1f - sy
            270 -> 1f - sy to sx
            else -> sx to sy
        }
        val size = (minOf(active.width(), active.height()) * 0.12f).roundToInt()
        val cx = (bx * active.width()).roundToInt()
        val cy = (by * active.height()).roundToInt()
        val l = (cx - size).coerceIn(0, active.width() - 2 * size)
        val t = (cy - size).coerceIn(0, active.height() - 2 * size)
        afRegion = MeteringRectangle(l, t, 2 * size, 2 * size, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        val d = device ?: return@post
        val s = session ?: return@post
        try {
            startRepeating()
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(previewSurface!!)
            applyCommon(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        } catch (_: Exception) {
        }
        // Return to continuous AF after a while.
        handler.postDelayed({ afRegion = null; startRepeating() }, 5000)
    }

    fun setTorch(on: Boolean) = handler.post {
        torch = on
        startRepeating()
    }

    /**
     * Captures a still. With [flash], the torch is lit long enough for auto-exposure to settle
     * (a robust stand-in for the precapture sequence across devices).
     */
    fun capture(flash: Boolean, jpegOrientation: Int, onDone: (CapturedPhoto?) -> Unit) {
        handler.post {
            val useTorch = flash && info?.hasFlash == true
            if (useTorch) {
                torch = true
                startRepeating()
                handler.postDelayed({ doCapture(jpegOrientation, useTorch, onDone) }, 700)
            } else {
                doCapture(jpegOrientation, false, onDone)
            }
        }
    }

    private fun doCapture(jpegOrientation: Int, usedTorch: Boolean, onDone: (CapturedPhoto?) -> Unit) {
        val d = device
        val s = session
        val jr = jpegReader
        if (d == null || s == null || jr == null) { main.post { onDone(null) }; return }
        val rr = rawReader
        var jpeg: ByteArray? = null
        var rawImage: android.media.Image? = null
        var result: TotalCaptureResult? = null
        var delivered = false

        // force = deliver whatever arrived (timeout / failure).
        fun finish(force: Boolean = false) {
            if (delivered) return
            val needRaw = rr != null
            if (!force && (jpeg == null || (needRaw && (rawImage == null || result == null)))) return
            delivered = true
            var dng: ByteArray? = null
            if (rawImage != null && result != null) {
                try {
                    val out = ByteArrayOutputStream()
                    DngCreator(chars!!, result!!).use { dc ->
                        dc.setOrientation(exifOrientation(jpegOrientation))
                        dc.writeImage(out, rawImage!!)
                    }
                    dng = out.toByteArray()
                } catch (_: Exception) {
                }
            }
            rawImage?.close()
            jr.setOnImageAvailableListener(null, null)
            rr?.setOnImageAvailableListener(null, null)
            if (usedTorch) { torch = false; startRepeating() }
            val photo = jpeg?.let { CapturedPhoto(it, dng) }
            main.post { onDone(photo) }
        }

        jr.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { img ->
                val buf = img.planes[0].buffer
                jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
            }
            finish()
        }, handler)
        rr?.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage()
            if (rawImage == null) rawImage = img else img?.close()
            finish()
        }, handler)

        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            rr?.let { b.addTarget(it.surface) }
            applyCommon(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    result = res
                    finish()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    finish(force = true)
                }
            }, handler)
            handler.postDelayed({ finish(force = true) }, 6000)
        } catch (e: CameraAccessException) {
            finish(force = true)
        } catch (e: Exception) {
            finish(force = true)
        }
    }

    private fun exifOrientation(deg: Int) = when (deg) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }

    fun close(done: (() -> Unit)? = null) = handler.post {
        openToken++
        closeInternal()
        done?.let { main.post(it) }
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        torch = false
        afRegion = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }
}
