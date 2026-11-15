package com.dazzdroid.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazzdroid.app.model.StampStyle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    fun changed() { tick++; vm.onSettingsChanged() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
      androidx.compose.runtime.key(tick) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Header("CAPTURE")
            val rawOk = vm.cameraInfo?.rawSupported == true
            Toggle(
                "Save RAW (DNG)",
                if (rawOk) "Unprocessed sensor data saved next to the filtered JPEG" else "This camera does not expose RAW capture",
                s.saveRaw && rawOk, enabled = rawOk,
            ) { s.saveRaw = it; changed() }
            Toggle("Keep original photo", "Also save the unfiltered camera JPEG", s.saveOriginal) { s.saveOriginal = it; changed() }
            Toggle("Film & instant borders", "Sprockets, 120 rebates and instant-film cards", s.showBorders) { s.showBorders = it; changed() }
            Toggle("Mirror front camera", "Save selfies as seen in the viewfinder", s.mirrorFront) { s.mirrorFront = it; changed() }
            Toggle("Shutter sound", null, s.shutterSound) { s.shutterSound = it; changed() }
            Toggle("Level indicator", "Horizon line in the viewfinder", s.showLevel) { s.showLevel = it; changed() }

            Header("PHOTO SIZE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048 to "2K", 3072 to "3K", 4096 to "4K", 8192 to "MAX").forEach { (v, l) ->
                    Chip(l, s.photoMaxEdge == v) { s.photoMaxEdge = v; changed() }
                }
            }
            Header("VIDEO QUALITY")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("720p", !s.videoHd) { s.videoHd = false; changed() }
                Chip("1080p", s.videoHd) { s.videoHd = true; changed() }
            }

            Header("DATE STAMP")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StampStyle.entries.forEach { st -> Chip(st.label.uppercase(), vm.stamp == st) { vm.setStampStyle(st); changed() } }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val custom = s.stampDate > 0
                Text(
                    if (custom) "Date: " + DateFormat.getDateInstance().format(Date(s.stampDate)) else "Date: capture time",
                    color = Color(0xFFE8E0D4), fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply { if (custom) timeInMillis = s.stampDate }
                    DatePickerDialog(ctx, { _, y, m, d ->
                        val c = Calendar.getInstance().apply { set(y, m, d) }
                        s.stampDate = c.timeInMillis
                        changed()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("EDIT", color = Amber, fontFamily = FontFamily.Monospace) }
                if (custom) TextButton(onClick = { s.stampDate = -1; changed() }) { Text("NOW", color = Amber, fontFamily = FontFamily.Monospace) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Photos are saved to Pictures/DazzDroid, videos to Movies/DazzDroid.", color = Muted, fontSize = 12.sp)
        }
      }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color(0xFFF2EDE4) else Muted, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}
