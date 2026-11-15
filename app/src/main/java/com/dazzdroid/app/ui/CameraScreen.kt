package com.dazzdroid.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dazzdroid.app.model.Adjustments
import com.dazzdroid.app.model.CaptureMode
import com.dazzdroid.app.model.Lens
import com.dazzdroid.app.model.Special
import com.dazzdroid.app.model.StampStyle
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun CameraScreen(vm: CameraViewModel, onImport: () -> Unit, onOpenLast: () -> Unit) {
    var panel by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        if (vm.message != null) {
            delay(2600)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, onSettings = { showSettings = true })

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Viewfinder(vm)
                androidx.compose.animation.AnimatedVisibility(
                    visible = panel != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xE6151311))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        when (panel) {
                            "adjust" -> AdjustPanel(vm)
                            "lens" -> LensPanel(vm)
                        }
                    }
                }
            }

            BottomControls(
                vm = vm,
                panel = panel,
                onPanel = { panel = if (panel == it) null else it },
                onImport = onImport,
                onOpenLast = onOpenLast,
            )
        }

        if (vm.screenFlash) Box(Modifier.fillMaxSize().background(Color.White))
    }

    if (showSettings) SettingsSheet(vm) { showSettings = false }
}

@Composable
private fun TopBar(vm: CameraViewModel, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.flash = !vm.flash }) {
            Icon(if (vm.flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = if (vm.flash) Amber else Color.White)
        }
        IconButton(onClick = { vm.timer = when (vm.timer) { 0 -> 3; 3 -> 10; else -> 0 } }) {
            Icon(
                when (vm.timer) { 3 -> Icons.Filled.Timer3; 10 -> Icons.Filled.Timer10; else -> Icons.Filled.TimerOff },
                "Self timer", tint = if (vm.timer > 0) Amber else Color.White,
            )
        }
        IconButton(onClick = { vm.toggleGrid() }) {
            Icon(if (vm.grid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Grid", tint = if (vm.grid) Amber else Color.White)
        }
        TextButton(onClick = { vm.cycleAspect() }) {
            Icon(Icons.Filled.AspectRatio, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(vm.aspect.label, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        TextButton(onClick = { vm.cycleStamp() }) {
            Icon(Icons.Filled.DateRange, null, tint = if (vm.stamp != StampStyle.OFF) Amber else Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(vm.stamp.label.uppercase(), color = if (vm.stamp != StampStyle.OFF) Amber else Color.White,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
    }
}

@Composable
private fun Viewfinder(vm: CameraViewModel) {
    @Suppress("UNUSED_VARIABLE") val settingsVersion = vm.settingsVersion // recompose on settings changes
    var focusPt by remember { mutableStateOf<Offset?>(null) }
    var focusTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(focusTime) {
        if (focusTime > 0) { delay(1200); focusPt = null }
    }
    Box(
        Modifier
            .aspectRatio(vm.aspect.ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = vm.setDisplay(h.surface, w, hh)
                        override fun surfaceDestroyed(h: SurfaceHolder) = vm.setDisplay(null, 0, 0)
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInputTap { off, w, h ->
                    focusPt = off; focusTime = System.nanoTime()
                    vm.focus(off.x / w, off.y / h)
                }
                .pointerInputZoom { vm.setZoomLevel(vm.zoom * it) },
        )
        if (vm.grid) GridOverlay()
        if (vm.settings.showLevel) LevelOverlay()
        focusPt?.let { p ->
            val d = LocalDensity.current
            val r = with(d) { 32.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((p.x - r).roundToInt(), (p.y - r).roundToInt()) }
                    .size(64.dp)
                    .border(1.5.dp, Amber, CircleShape),
            )
        }

        // HUD
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.camera.name + if (vm.camera.variants.size > 1) " · " + vm.variantSpec.name else "",
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (vm.zoom > 1.01f) {
                Spacer(Modifier.width(6.dp))
                Text(String.format(java.util.Locale.US, "%.1fx", vm.zoom), color = Amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (vm.lens != Lens.NONE) {
                Spacer(Modifier.width(6.dp))
                Text(vm.lens.label.uppercase(), color = Amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        if (vm.recording) RecordingBadge(vm.recordStart, Modifier.align(Alignment.TopEnd).padding(8.dp))
        val pending = when {
            vm.halfPending -> "HALF 1/2"
            vm.doublePending -> "EXPOSURE 2/2"
            vm.doubleExposure -> "DOUBLE EXP"
            vm.burstProgress > 0 -> "LENS ${vm.burstProgress}/4"
            else -> null
        }
        pending?.let {
            Text(it, color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Amber)
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (vm.countdown > 0) {
            Text("${vm.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        }
        if (vm.busy && !vm.recording) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center).size(42.dp))
        }
        vm.message?.let {
            Text(it, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

private fun Modifier.pointerInputTap(onTap: (Offset, Float, Float) -> Unit) =
    this.pointerInput("tap") {
        detectTapGestures { off -> onTap(off, size.width.toFloat(), size.height.toFloat()) }
    }

private fun Modifier.pointerInputZoom(onZoom: (Float) -> Unit) =
    this.pointerInput("zoom") {
        detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) }
    }

@Composable
private fun RecordingBadge(start: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val s = ((now - start) / 1000).coerceAtLeast(0)
    Row(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if ((now / 500) % 2 == 0L) RecRed else Color.Transparent))
        Spacer(Modifier.width(6.dp))
        Text(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x66FFFFFF)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
        }
    }
}

@Composable
private fun LevelOverlay() {
    val ctx = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val r = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                roll = roll * 0.8f + r * 0.2f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    val level = kotlin.math.abs(roll) < 1.5f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(140.dp).height(2.dp).rotate(roll)) {
            drawLine(if (level) Color(0xFF66BB6A) else Color.White, Offset(0f, 0f), Offset(size.width, 0f), 3f)
        }
        Canvas(Modifier.size(10.dp)) { drawCircle(if (level) Color(0xFF66BB6A) else Color.White, style = Stroke(2f)) }
    }
}

private val SHUTTERS = listOf(
    "1/8000" to 125_000L, "1/4000" to 250_000L, "1/2000" to 500_000L, "1/1000" to 1_000_000L,
    "1/500" to 2_000_000L, "1/250" to 4_000_000L, "1/125" to 8_000_000L, "1/60" to 16_666_667L,
    "1/30" to 33_333_333L, "1/15" to 66_666_667L, "1/8" to 125_000_000L, "1/4" to 250_000_000L,
    "1/2" to 500_000_000L, "1s" to 1_000_000_000L,
)

@Composable
private fun AdjustPanel(vm: CameraViewModel) {
    val a = vm.adj
    val info = vm.cameraInfo
    Column {
        LabeledSlider("EV", String.format(java.util.Locale.US, "%+.1f", a.ev), a.ev, -2f..2f, { vm.updateAdjustments(a.copy(ev = (it * 10).roundToInt() / 10f)) })
        LabeledSlider("TEMP", "${a.kelvin}K", a.kelvin.toFloat(), 2000f..10000f, { vm.updateAdjustments(a.copy(kelvin = (it / 100).roundToInt() * 100)) })
        LabeledSlider("TINT", String.format(java.util.Locale.US, "%+d", (a.tint * 50).roundToInt()), a.tint, -1f..1f, { vm.updateAdjustments(a.copy(tint = it)) })
        if (info?.manualSupported == true && info.isoRange != null) {
            val lo = info.isoRange.lower.toFloat().coerceAtLeast(25f)
            val hi = info.isoRange.upper.toFloat()
            val pos = a.iso?.let { (kotlin.math.ln(it / lo) / kotlin.math.ln(hi / lo)).toFloat().coerceIn(0.02f, 1f) } ?: 0f
            LabeledSlider("ISO", a.iso?.toString() ?: "AUTO", pos, 0f..1f, { v ->
                vm.updateAdjustments(a.copy(iso = if (v < 0.02f) null else (lo * (hi / lo).pow(v)).roundToInt()))
            })
            val range = info.exposureRange
            val opts = SHUTTERS.filter { range == null || (it.second >= range.lower && it.second <= range.upper) }
            if (opts.isNotEmpty()) {
                val idx = a.shutterNs?.let { s -> opts.indexOfFirst { it.second == s } + 1 } ?: 0
                LabeledSlider("SHUTTER", if (idx == 0) "AUTO" else opts[idx - 1].first, idx.toFloat(), 0f..opts.size.toFloat(), { v ->
                    val i = v.roundToInt()
                    vm.updateAdjustments(a.copy(shutterNs = if (i == 0) null else opts[i - 1].second))
                }, steps = opts.size - 1)
            }
        }
        LabeledSlider("FILTER", "${(a.intensity * 100).roundToInt()}%", a.intensity, 0f..1f, { vm.updateAdjustments(a.copy(intensity = it)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.resetAdjustments() }) { Text("RESET", color = Amber, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun LensPanel(vm: CameraViewModel) {
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lens.entries.forEach { l -> Chip(l.label.uppercase(), vm.lens == l) { vm.chooseLens(l) } }
        }
        if (vm.lens != Lens.NONE) {
            LabeledSlider("STRENGTH", "${(vm.lensAmount * 100).roundToInt()}%", vm.lensAmount, 0f..1f, { vm.chooseLensAmount(it) })
        }
    }
}

@Composable
private fun BottomControls(
    vm: CameraViewModel,
    panel: String?,
    onPanel: (String) -> Unit,
    onImport: () -> Unit,
    onOpenLast: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            vm.camera.description, color = Muted, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (vm.camera.variants.size > 1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                vm.camera.variants.forEachIndexed { i, v ->
                    Chip(v.name, vm.variant == i) { vm.selectVariant(i) }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Filled.AddPhotoAlternate, "IMPORT", onClick = onImport)
            ToolButton(Icons.Filled.Tune, "ADJUST", panel == "adjust") { onPanel("adjust") }
            ToolButton(Icons.Filled.Camera, "LENS", panel == "lens" || vm.lens != Lens.NONE) { onPanel("lens") }
            if (vm.mode == CaptureMode.PHOTO && vm.camera.special == Special.NONE) {
                ToolButton(Icons.Filled.Layers, "DOUBLE", vm.doubleExposure) { vm.toggleDouble() }
            }
        }
        CameraCarousel(vm.visibleCameras, vm.camera) { vm.selectCamera(it) }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            listOf(CaptureMode.PHOTO, CaptureMode.VIDEO).forEach { m ->
                Text(
                    m.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (vm.mode == m) Amber else Muted,
                    modifier = Modifier.clickable { vm.selectMode(m) }.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2724))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(10.dp)).clickable(onClick = onOpenLast),
                contentAlignment = Alignment.Center,
            ) {
                val t = vm.thumb
                if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.PhotoLibrary, null, tint = Muted)
            }
            ShutterButton(vm.mode == CaptureMode.VIDEO, vm.recording, vm.busy) { vm.shutter() }
            IconButton(onClick = { vm.flipCamera() }, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2A2724))) {
                Icon(Icons.Filled.Cameraswitch, "Switch camera", tint = Color.White)
            }
        }
    }
}

@Suppress("unused")
private fun Adjustments.isDefault() = this == Adjustments()
