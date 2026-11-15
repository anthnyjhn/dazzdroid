package com.dazzdroid.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazzdroid.app.gl.GlProcessor
import com.dazzdroid.app.gl.LookBuilder
import com.dazzdroid.app.media.DateStamp
import com.dazzdroid.app.media.MediaSaver
import com.dazzdroid.app.media.PhotoPipeline
import com.dazzdroid.app.media.VideoTranscoder
import com.dazzdroid.app.model.Adjustments
import com.dazzdroid.app.model.Aspect
import com.dazzdroid.app.model.CameraModel
import com.dazzdroid.app.model.Lens
import com.dazzdroid.app.model.Special
import com.dazzdroid.app.model.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

/** Applies cameras retroactively to an imported photo or video. */
@Composable
fun EditorScreen(vm: CameraViewModel, uri: Uri, isVideo: Boolean, onClose: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { GlProcessor.get(ctx) }
    val cameras = remember { vm.cameras.filter { it.special == Special.NONE } }

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<CameraModel>(cameras.firstOrNull { it.id == vm.camera.id } ?: cameras[1]) }
    var variant by remember { mutableIntStateOf(0) }
    var adj by remember { mutableStateOf(Adjustments()) }
    var lens by remember { mutableStateOf(Lens.NONE) }
    var aspect by remember { mutableStateOf<Aspect?>(null) }
    var stamp by remember { mutableStateOf(vm.stamp) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cancelled by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            source = withContext(Dispatchers.IO) {
                if (isVideo) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, uri)
                        val f = r.getFrameAtTime(0) ?: error("Could not read video")
                        PhotoPipeline.thumbnail(f, 1080)
                    } finally { r.release() }
                } else PhotoPipeline.decodeUri(ctx, uri, 1280)
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not open file"
        }
    }

    fun look(seed: Float = 7f) = LookBuilder.build(
        camera.variants[variant.coerceIn(0, camera.variants.lastIndex)].spec, adj, lens, 0.6f, false, adj.ev, seed,
    )

    fun outAspect(src: Bitmap): Float? = aspect?.let { a -> if (src.width > src.height) 1f / a.ratio else a.ratio }

    LaunchedEffect(source, camera, variant, adj, lens, aspect, stamp) {
        val src = source ?: return@LaunchedEffect
        try {
            val img = processor.process(src, look(), if (isVideo) null else outAspect(src), 1.37f)
            preview = withContext(Dispatchers.Default) {
                if (isVideo) {
                    if (stamp != StampStyle.OFF) {
                        val m = img.copy(Bitmap.Config.ARGB_8888, true)
                        DateStamp.draw(android.graphics.Canvas(m), m.width, m.height, stamp, Date())
                        m
                    } else img
                } else PhotoPipeline.finish(img, camera, stamp, stampDate(vm), vm.settings.showBorders, 1)
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun save() {
        saving = true
        scope.launch {
            val msg = try {
                withContext(Dispatchers.Default) {
                    if (isVideo) {
                        val src = source!!
                        val overlay = DateStamp.overlay(1080, (1080f * src.height / src.width).roundToInt(), stamp, Date())
                        val out = File(ctx.cacheDir, "import_${System.nanoTime()}.mp4")
                        VideoTranscoder.transcode(ctx, uri, out, look(), overlay, if (vm.settings.videoHd) 1080 else 720,
                            { p -> progress = p }, { cancelled })
                        val saved = MediaSaver.saveVideoFile(ctx, out)
                        out.delete()
                        if (saved != null) "Video saved" else "Could not save video"
                    } else {
                        val full = PhotoPipeline.decodeUri(ctx, uri, vm.settings.photoMaxEdge)
                        val img = processor.process(full, look(kotlin.random.Random.nextFloat() * 100f), outAspect(full), 1.37f)
                        full.recycle()
                        val final = PhotoPipeline.finish(img, camera, stamp, stampDate(vm), vm.settings.showBorders, 1)
                        if (MediaSaver.saveJpeg(ctx, final) != null) "Photo saved" else "Could not save photo"
                    }
                }
            } catch (e: Exception) {
                if (cancelled) "Export cancelled" else "Export failed: ${e.message}"
            }
            saving = false
            onClose(msg)
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cancelled = true; onClose(null) }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Text(if (isVideo) "IMPORT VIDEO" else "IMPORT PHOTO", color = Color.White, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            Button(onClick = { save() }, enabled = !saving && source != null,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)) {
                Text("SAVE", fontFamily = FontFamily.Monospace)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val p = preview
            when {
                error != null -> Text(error!!, color = RecRed)
                p != null -> Image(p.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> CircularProgressIndicator(color = Amber)
            }
            if (saving) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (isVideo) "Developing ${(progress * 100).roundToInt()}%" else "Developing…",
                        color = Color.White, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    if (isVideo) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Amber)
                    else CircularProgressIndicator(color = Amber, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (camera.variants.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                camera.variants.forEachIndexed { i, v -> Chip(v.name, variant == i) { variant = i }; Spacer(Modifier.width(6.dp)) }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            LabeledSlider("EV", String.format(java.util.Locale.US, "%+.1f", adj.ev), adj.ev, -2f..2f, { adj = adj.copy(ev = (it * 10).roundToInt() / 10f) })
            LabeledSlider("TEMP", "${adj.kelvin}K", adj.kelvin.toFloat(), 2000f..10000f, { adj = adj.copy(kelvin = (it / 100).roundToInt() * 100) })
            LabeledSlider("FILTER", "${(adj.intensity * 100).roundToInt()}%", adj.intensity, 0f..1f, { adj = adj.copy(intensity = it) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LENS", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Lens.entries.forEach { l -> Chip(l.label.uppercase(), lens == l) { lens = l } }
            Spacer(Modifier.width(8.dp))
            Text("DATE", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            StampStyle.entries.forEach { s -> Chip(s.label.uppercase(), stamp == s) { stamp = s } }
        }
        if (!isVideo) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CROP", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Chip("ORIGINAL", aspect == null) { aspect = null }
                Aspect.entries.forEach { a -> Chip(a.label, aspect == a) { aspect = a } }
            }
        }
        CameraCarousel(cameras, camera) { camera = it; variant = 0 }
        Spacer(Modifier.height(12.dp))
    }
}

private fun stampDate(vm: CameraViewModel): Date = vm.settings.stampDate.let { if (it > 0) Date(it) else Date() }
