package com.dazzdroid.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazzdroid.app.camera.CameraController
import com.dazzdroid.app.camera.CameraInfo
import com.dazzdroid.app.gl.GlProcessor
import com.dazzdroid.app.gl.LookBuilder
import com.dazzdroid.app.gl.PreviewRenderer
import com.dazzdroid.app.media.AudioCapture
import com.dazzdroid.app.media.Darkroom
import com.dazzdroid.app.media.DateStamp
import com.dazzdroid.app.media.LoopEncoder
import com.dazzdroid.app.media.MediaSaver
import com.dazzdroid.app.media.MuxerSink
import com.dazzdroid.app.media.PhotoPipeline
import com.dazzdroid.app.media.SurfaceEncoder
import com.dazzdroid.app.model.Adjustments
import com.dazzdroid.app.model.AppSettings
import com.dazzdroid.app.model.Aspect
import com.dazzdroid.app.model.CameraCatalog
import com.dazzdroid.app.model.CameraModel
import com.dazzdroid.app.model.CaptureMode
import com.dazzdroid.app.model.Lens
import com.dazzdroid.app.model.Look
import com.dazzdroid.app.model.Special
import com.dazzdroid.app.model.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    val cameras: List<CameraModel> = CameraCatalog.load(app)
    val renderer = PreviewRenderer(app)
    private val controller = CameraController(app)
    private val processor = GlProcessor.get(app)
    private val sound = MediaActionSound()
    private var surfaceTexture: SurfaceTexture? = null
    private var resumed = false
    private var openKey: String? = null

    var mode by mutableStateOf(CaptureMode.PHOTO); private set
    var camera by mutableStateOf(cameras.firstOrNull { it.id == settings.lastCamera } ?: cameras[1]); private set
    var variant by mutableIntStateOf(0); private set
    var aspect by mutableStateOf(camera.aspect); private set
    var front by mutableStateOf(false); private set
    var flash by mutableStateOf(false)
    var timer by mutableIntStateOf(0)
    var grid by mutableStateOf(settings.grid); private set
    var stamp by mutableStateOf(settings.stamp); private set
    var lens by mutableStateOf(Lens.NONE); private set
    var lensAmount by mutableFloatStateOf(0.6f); private set
    var adj by mutableStateOf(Adjustments()); private set
    var doubleExposure by mutableStateOf(false); private set
    var doublePending by mutableStateOf(false); private set
    var halfPending by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var countdown by mutableIntStateOf(0); private set
    var recording by mutableStateOf(false); private set
    var recordStart by mutableLongStateOf(0L); private set
    var thumb by mutableStateOf<Bitmap?>(null); private set
    var lastUri by mutableStateOf<Uri?>(null); private set
    var message by mutableStateOf<String?>(null)
    var cameraInfo by mutableStateOf<CameraInfo?>(null); private set
    var screenFlash by mutableStateOf(false); private set
    var zoom by mutableFloatStateOf(1f); private set
    var burstProgress by mutableIntStateOf(0); private set
    var settingsVersion by mutableIntStateOf(0); private set

    @Volatile var deviceOrientation = 0

    private var leakSeed = Random.nextFloat() * 100f
    private var doubleFirst: Bitmap? = null
    private var halfFirst: Bitmap? = null
    private var frameNo = 1
    private var stampJob: Job? = null

    // recording
    private var sink: MuxerSink? = null
    private var audio: AudioCapture? = null
    private var recFile: File? = null

    init {
        renderer.start { st ->
            surfaceTexture = st
            if (resumed) openCamera()
        }
        pushLook()
        refreshStamp()
        stampJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                refreshStamp()
            }
        }
    }

    val variantSpec get() = camera.variants[variant.coerceIn(0, camera.variants.lastIndex)]

    val visibleCameras: List<CameraModel>
        get() = if (mode == CaptureMode.VIDEO) cameras.filter { it.supportsVideo }.sortedByDescending { it.isVideoCamera }
        else cameras

    // ---------------------------------------------------------------- lifecycle

    fun onResume() {
        resumed = true
        if (surfaceTexture != null) openCamera()
    }

    fun onPause() {
        resumed = false
        if (recording) stopRecording()
        openKey = null
        controller.close()
    }

    override fun onCleared() {
        controller.release()
        renderer.release()
        sound.release()
        stampJob?.cancel()
    }

    fun setDisplay(surface: Surface?, w: Int, h: Int) = renderer.setDisplay(surface, w, h)

    private fun openCamera(force: Boolean = false) {
        val st = surfaceTexture ?: return
        val wide = aspect == Aspect.R9_16
        val raw = settings.saveRaw && mode == CaptureMode.PHOTO
        val key = "$front/$wide/$raw"
        if (!force && key == openKey) return
        openKey = key
        controller.open(front, st, wide, raw) { info ->
            cameraInfo = info
            if (info == null) {
                message = "Camera unavailable"
                openKey = null
                return@open
            }
            renderer.setSource(info.previewSize.width, info.previewSize.height, info.sensorOrientation)
            controller.setExposureCompensation(adj.ev)
            controller.setManual(adj.iso, adj.shutterNs)
            controller.setZoom(zoom)
            pushLook()
        }
    }

    // ---------------------------------------------------------------- selections

    fun selectMode(m: CaptureMode) {
        if (recording || m == mode) return
        mode = m
        if (m == CaptureMode.VIDEO) {
            if (!camera.supportsVideo) selectCamera(cameras.first { it.isVideoCamera })
            doubleExposure = false; clearPending()
            if (stamp == StampStyle.OFF) camera.defaultStamp?.let { setStampStyle(it, persist = false) }
        }
        openCamera()
    }

    fun selectCamera(c: CameraModel) {
        if (recording) return
        if (c.id == camera.id) return
        camera = c
        variant = 0
        settings.lastCamera = c.id
        clearPending()
        chooseAspect(c.aspect)
        if (mode == CaptureMode.VIDEO && c.defaultStamp != null) setStampStyle(c.defaultStamp, persist = false)
        pushLook()
    }

    fun selectVariant(i: Int) { variant = i; pushLook() }

    fun chooseAspect(a: Aspect) {
        if (recording) return
        aspect = a
        refreshStamp()
        openCamera()
    }

    fun cycleAspect() {
        val all = Aspect.entries
        chooseAspect(all[(all.indexOf(aspect) + 1) % all.size])
    }

    fun flipCamera() {
        if (recording) return
        front = !front
        zoom = 1f
        openCamera()
    }

    fun toggleGrid() { grid = !grid; settings.grid = grid }

    fun setStampStyle(s: StampStyle, persist: Boolean = true) {
        stamp = s
        if (persist) settings.stamp = s
        refreshStamp()
    }

    fun cycleStamp() {
        val all = StampStyle.entries
        setStampStyle(all[(all.indexOf(stamp) + 1) % all.size])
    }

    fun chooseLens(l: Lens) { lens = l; pushLook() }
    fun chooseLensAmount(a: Float) { lensAmount = a; pushLook() }

    fun updateAdjustments(a: Adjustments) {
        val old = adj
        adj = a
        if (a.ev != old.ev) controller.setExposureCompensation(a.ev)
        if (a.iso != old.iso || a.shutterNs != old.shutterNs) controller.setManual(a.iso, a.shutterNs)
        pushLook()
    }

    fun resetAdjustments() = updateAdjustments(Adjustments())

    fun setZoomLevel(z: Float) {
        val max = cameraInfo?.maxZoom ?: 1f
        zoom = z.coerceIn(1f, maxOf(1f, max))
        controller.setZoom(zoom)
    }

    fun focus(nx: Float, ny: Float) = controller.focusAt(nx, ny)

    fun toggleDouble() {
        doubleExposure = !doubleExposure
        if (!doubleExposure) {
            doubleFirst = null; doublePending = false
            renderer.setDoubleExposure(null)
        }
    }

    fun onSettingsChanged() {
        settingsVersion++
        refreshStamp()
        openCamera()
    }

    private fun clearPending() {
        halfFirst = null; halfPending = false
        doubleFirst = null; doublePending = false
        renderer.setDoubleExposure(null)
    }

    private fun evInShader(): Float {
        val inf = cameraInfo
        return if (inf == null || inf.evRange.lower == inf.evRange.upper) adj.ev else 0f
    }

    private fun currentLook(withFlash: Boolean): Look =
        LookBuilder.build(variantSpec.spec, adj, lens, lensAmount, withFlash, evInShader(), leakSeed)

    private fun pushLook() {
        renderer.look = currentLook(false)
    }

    private fun stampDate(): Date = settings.stampDate.let { if (it > 0) Date(it) else Date() }

    private fun refreshStamp() {
        val w = 1080
        val h = (w / aspect.ratio).roundToInt()
        val showInPreview = stamp != StampStyle.OFF && camera.special == Special.NONE
        renderer.setOverlay(if (showInPreview) DateStamp.overlay(w, h, stamp, stampDate()) else null)
    }

    // ---------------------------------------------------------------- capture

    fun shutter() {
        if (busy || countdown > 0) return
        if (mode == CaptureMode.VIDEO) {
            if (recording) stopRecording() else withTimer { startRecording() }
            return
        }
        withTimer { capture() }
    }

    private fun withTimer(action: () -> Unit) {
        if (timer <= 0) { action(); return }
        viewModelScope.launch {
            for (i in timer downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0
            action()
        }
    }

    private fun click() { if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK) }

    private fun jpegOrientation(): Int {
        val inf = cameraInfo ?: return 90
        var dev = ((deviceOrientation + 45) / 90 * 90) % 360
        if (inf.front) dev = -dev
        return (inf.sensorOrientation + dev + 360) % 360
    }

    private fun capture() {
        when (camera.special) {
            Special.MULTI4 -> burst(4, 260L) { frames -> Darkroom.grid4(frames) }
            Special.WIGGLE -> wiggle()
            else -> stillCapture()
        }
    }

    private suspend fun frontFlash(on: Boolean) {
        if (front && flash) {
            screenFlash = on
            if (on) delay(350)
        }
    }

    private fun stillCapture() {
        busy = true
        val cam = camera
        val look = currentLook(flash)
        val raw = settings.saveRaw
        val mirror = front && settings.mirrorFront
        val special = cam.special
        val asp = if (special == Special.HALF) Aspect.R3_4.ratio else aspect.ratio
        viewModelScope.launch {
            frontFlash(true)
            val photo = suspendCancellableCoroutine { cont ->
                controller.capture(flash && !front, jpegOrientation()) { cont.resume(it) }
            }
            frontFlash(false)
            if (photo == null) { busy = false; message = "Capture failed"; return@launch }
            click()
            try {
                withContext(Dispatchers.Default) {
                    val img = PhotoPipeline.decodeBytes(photo.jpeg, settings.photoMaxEdge, mirror)
                    val landscape = img.width > img.height
                    val outA = if (landscape) 1f / asp else asp
                    val dbl = doubleFirst
                    val processed = processor.process(img, look, outA, Random.nextFloat() * 1000f, if (doubleExposure) dbl else null)
                    img.recycle()
                    val stem = MediaSaver.name("DAZZ")
                    if (raw && photo.dng != null) MediaSaver.saveBytes(getApplication(), photo.dng, "$stem.dng", "image/x-adobe-dng")
                    if (settings.saveOriginal) MediaSaver.saveBytes(getApplication(), photo.jpeg, "${stem}_ORIG.jpg", "image/jpeg")

                    if (doubleExposure && dbl == null) {
                        doubleFirst = processed
                        renderer.setDoubleExposure(processed)
                        doublePending = true
                        message = "First exposure set. Shoot the second frame."
                        return@withContext
                    }
                    if (doubleExposure) {
                        doubleFirst = null
                        doublePending = false
                        renderer.setDoubleExposure(null)
                    }
                    var composite = processed
                    if (special == Special.HALF) {
                        val first = halfFirst
                        if (first == null) {
                            halfFirst = processed
                            halfPending = true
                            message = "Half frame 1/2. Shoot the second half."
                            return@withContext
                        }
                        composite = Darkroom.halfFrame(first, processed)
                        halfFirst = null
                        halfPending = false
                    }
                    saveFinal(cam, composite)
                }
            } catch (e: Exception) {
                message = "Processing failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun saveFinal(cam: CameraModel, img: Bitmap) {
        val final = PhotoPipeline.finish(img, cam, stamp, stampDate(), settings.showBorders, frameNo++)
        val uri = MediaSaver.saveJpeg(getApplication(), final)
        val t = PhotoPipeline.thumbnail(final)
        withContext(Dispatchers.Main) {
            thumb = t
            lastUri = uri
            leakSeed = Random.nextFloat() * 100f
            pushLook()
            if (uri == null) message = "Could not save photo"
        }
    }

    private suspend fun snapshot(w: Int, h: Int): Bitmap = suspendCancellableCoroutine { cont ->
        renderer.snapshot(w, h) { cont.resume(it) }
    }

    private fun snapSize(): Pair<Int, Int> {
        val w = 1080
        return w to ((w / aspect.ratio).roundToInt() / 2 * 2)
    }

    private fun burst(count: Int, intervalMs: Long, compose: (List<Bitmap>) -> Bitmap) {
        busy = true
        val cam = camera
        viewModelScope.launch {
            if (flash && !front) controller.setTorch(true)
            frontFlash(true)
            if (flash) delay(500)
            val (w, h) = snapSize()
            val frames = ArrayList<Bitmap>()
            for (i in 0 until count) {
                burstProgress = i + 1
                click()
                frames += snapshot(w, h)
                delay(intervalMs)
            }
            burstProgress = 0
            frontFlash(false)
            if (flash && !front) controller.setTorch(false)
            try {
                withContext(Dispatchers.Default) { saveFinal(cam, compose(frames)) }
            } catch (e: Exception) {
                message = "Processing failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun wiggle() {
        busy = true
        viewModelScope.launch {
            val (w, h) = snapSize()
            val frames = ArrayList<Bitmap>()
            for (i in 0 until 4) {
                burstProgress = i + 1
                click()
                frames += snapshot(w, h)
                delay(140)
            }
            burstProgress = 0
            try {
                withContext(Dispatchers.Default) {
                    // Each "lens" sees the scene from a slightly different position.
                    val cw = (w * 0.94f).roundToInt()
                    val ch = (h * 0.94f).roundToInt()
                    val shifted = frames.mapIndexed { i, b ->
                        val dx = ((w - cw) / 2 + (i - 1.5f) * w * 0.012f).roundToInt().coerceIn(0, w - cw)
                        Bitmap.createBitmap(b, dx, (h - ch) / 2, cw, ch)
                    }
                    val stampDateNow = stampDate()
                    val final = shifted.map {
                        val m = it.copy(Bitmap.Config.ARGB_8888, true)
                        if (stamp != StampStyle.OFF) DateStamp.draw(android.graphics.Canvas(m), m.width, m.height, stamp, stampDateNow)
                        m
                    }
                    val f = File(getApplication<Application>().cacheDir, "wiggle_${System.nanoTime()}.mp4")
                    LoopEncoder.encode(getApplication(), final, f)
                    val uri = MediaSaver.saveVideoFile(getApplication(), f, "DAZZ3D")
                    f.delete()
                    val t = PhotoPipeline.thumbnail(final[0])
                    withContext(Dispatchers.Main) { thumb = t; lastUri = uri; message = "3D wiggle saved" }
                }
            } catch (e: Exception) {
                message = "Processing failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- video

    private fun videoSize(): Pair<Int, Int> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val caps = list.codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        val bases = if (settings.videoHd) listOf(1080, 720, 480) else listOf(720, 480)
        for (b in bases) {
            val w = b
            val h = (b / aspect.ratio / 16f).roundToInt() * 16
            if (caps.isEmpty() || caps.any { it.isSizeSupported(w, h) }) return w to h
        }
        return 480 to ((480 / aspect.ratio / 16f).roundToInt() * 16)
    }

    private fun startRecording() {
        if (recording) return
        val ctx = getApplication<Application>()
        try {
            val (w, h) = videoSize()
            val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val f = File(ctx.cacheDir, "rec_${System.nanoTime()}.mp4")
            val hint = ((deviceOrientation + 45) / 90 * 90) % 360
            val s = MuxerSink(f.absolutePath, if (hasMic) 2 else 1, hint)
            s.setBaseUs(System.nanoTime() / 1000)
            val enc = SurfaceEncoder(w, h, SurfaceEncoder.bitrateFor(w, h), 30, s)
            sink = s
            recFile = f
            if (hasMic) audio = AudioCapture(s).also { it.start() }
            renderer.startRecording(enc)
            renderer.snapshot(240, (240 / aspect.ratio).roundToInt()) { thumb = it }
            if (settings.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            recording = true
            recordStart = System.currentTimeMillis()
            if (flash && !front) controller.setTorch(true)
        } catch (e: Exception) {
            message = "Could not start recording: ${e.message}"
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        busy = true
        if (flash && !front) controller.setTorch(false)
        if (settings.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        val s = sink
        val a = audio
        val f = recFile
        sink = null; audio = null; recFile = null
        renderer.stopRecording {
            viewModelScope.launch(Dispatchers.IO) {
                a?.stop()
                s?.stop()
                val uri = if (f != null && f.length() > 0 && s?.hasStarted == true) MediaSaver.saveVideoFile(getApplication(), f) else null
                f?.delete()
                withContext(Dispatchers.Main) {
                    busy = false
                    if (uri != null) {
                        lastUri = uri
                        message = "Video saved"
                    } else message = "Recording failed"
                }
            }
        }
    }
}
