package com.dazzdroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazzdroid.app.model.CameraModel
import com.dazzdroid.app.model.Special

/** A tiny illustrated camera body, shaped by the camera's category. */
@Composable
fun CameraGlyph(cam: CameraModel, selected: Boolean, modifier: Modifier = Modifier) {
    val body = Color(cam.color)
    val trim = if (body.luminance() > 0.5f) Color(0xFF2A2724) else Color(0xFFEDE6DA)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (cam.category) {
            "Instant" -> {
                drawRoundRect(body, Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, h * 0.66f), CornerRadius(w * 0.12f))
                drawCircle(Color(0xFF151515), w * 0.17f, Offset(w * 0.5f, h * 0.5f))
                drawCircle(trim.copy(alpha = 0.6f), w * 0.17f, Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.03f))
                drawRect(trim, Offset(w * 0.2f, h * 0.24f), Size(w * 0.14f, h * 0.08f))
            }
            "Medium" -> {
                drawRoundRect(body, Offset(w * 0.2f, h * 0.15f), Size(w * 0.6f, h * 0.7f), CornerRadius(w * 0.06f))
                drawRect(trim.copy(alpha = 0.35f), Offset(w * 0.2f, h * 0.15f), Size(w * 0.6f, h * 0.14f))
                drawCircle(Color(0xFF151515), w * 0.2f, Offset(w * 0.5f, h * 0.58f))
                drawCircle(trim.copy(alpha = 0.5f), w * 0.2f, Offset(w * 0.5f, h * 0.58f), style = Stroke(w * 0.03f))
            }
            "Video" -> {
                drawRoundRect(body, Offset(w * 0.1f, h * 0.28f), Size(w * 0.58f, h * 0.44f), CornerRadius(w * 0.06f))
                drawRect(body, Offset(w * 0.68f, h * 0.36f), Size(w * 0.2f, h * 0.28f))
                drawCircle(Color(0xFF151515), w * 0.11f, Offset(w * 0.78f, h * 0.5f))
                drawCircle(Color(0xFFE53935), w * 0.04f, Offset(w * 0.2f, h * 0.36f))
            }
            else -> {
                drawRoundRect(body, Offset(w * 0.08f, h * 0.26f), Size(w * 0.84f, h * 0.5f), CornerRadius(w * 0.08f))
                drawRect(trim.copy(alpha = 0.3f), Offset(w * 0.08f, h * 0.26f), Size(w * 0.84f, h * 0.1f))
                drawRect(trim, Offset(w * 0.66f, h * 0.31f), Size(w * 0.16f, h * 0.09f))
                val lensR = if (cam.special == Special.MULTI4) w * 0.07f else w * 0.15f
                if (cam.special == Special.MULTI4 || cam.special == Special.WIGGLE) {
                    val n = 4
                    for (i in 0 until n) {
                        val cx = w * (0.26f + i * 0.16f)
                        drawCircle(Color(0xFF151515), w * 0.065f, Offset(cx, h * 0.56f))
                    }
                } else {
                    drawCircle(Color(0xFF151515), lensR, Offset(w * 0.45f, h * 0.53f))
                    drawCircle(trim.copy(alpha = 0.55f), lensR, Offset(w * 0.45f, h * 0.53f), style = Stroke(w * 0.03f))
                }
                drawRect(trim.copy(alpha = 0.8f), Offset(w * 0.18f, h * 0.2f), Size(w * 0.12f, h * 0.06f))
            }
        }
        if (selected) drawCircle(Color(0xFFFFB347), w * 0.05f, Offset(w * 0.9f, h * 0.12f))
    }
}

@Composable
fun CameraCarousel(cameras: List<CameraModel>, selected: CameraModel, onSelect: (CameraModel) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected.id, cameras.size) {
        val i = cameras.indexOfFirst { it.id == selected.id }
        if (i >= 0) state.animateScrollToItem(maxOf(0, i - 2))
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        itemsIndexed(cameras, key = { _, c -> c.id }) { _, cam ->
            val sel = cam.id == selected.id
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Color(0xFF2E2A26) else Color.Transparent)
                    .border(1.dp, if (sel) Amber else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(cam) }
                    .padding(vertical = 6.dp),
            ) {
                CameraGlyph(cam, sel, Modifier.size(46.dp))
                Text(
                    cam.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    color = if (sel) Amber else Color(0xFFD8D0C4), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Amber else Color(0xFF2A2724))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (selected) Color.Black else Color(0xFFE8E0D4), fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label, tint = if (active) Amber else Color(0xFFE8E0D4))
        }
        Text(label, fontSize = 9.sp, color = if (active) Amber else Muted, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(label, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(valueText, fontSize = 11.sp, color = Color(0xFFE8E0D4), fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun ShutterButton(video: Boolean, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color(0xFFF2EDE4), CircleShape)
            .clickable(enabled = !busy || recording, onClick = onClick),
    ) {
        val inner = when {
            recording -> Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(RecRed)
            video -> Modifier.size(58.dp).clip(CircleShape).background(RecRed)
            busy -> Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF6B655E))
            else -> Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFF2EDE4))
        }
        Box(inner)
    }
}
