package com.example.screencapture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Pink = Color(0xFFEC4899)
private val Purple = Color(0xFF7C3AED)
private val Amber = Color(0xFFFBBF24)
private val Ink = Color(0xFF2E1065)
private val Muted = Color(0xFF6B5B8A)
private val CaptureBrush = Brush.horizontalGradient(listOf(Pink, Purple))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { CaptureHome() } }

    @Composable
    private fun CaptureHome() {
        val context = this
        var status by remember { mutableStateOf("啟用後可切換至任何畫面；懸浮按鈕會擷取整個螢幕。") }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ContextCompat.startForegroundService(context, FloatingCaptureService.startIntent(context, result.resultCode, result.data!!))
                status = "懸浮擷取按鈕已啟用"
            } else status = "未取得 Android 系統螢幕擷取授權"
        }
        MaterialTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFF1F8), Color(0xFFF3EDFF), Color(0xFFFFF8EC))))
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Header()
                    StepsCard()
                    ActionButton("啟用懸浮擷取按鈕", "授權後在任何畫面顯示懸浮按鈕", R.drawable.ic_capture, CaptureBrush) {
                        if (Settings.canDrawOverlays(context)) projectionLauncher.launch(manager.createScreenCaptureIntent())
                        else {
                            status = "請允許「顯示在其他 App 上層」後，再點一次啟用。"
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    }
                    ActionButton("停用懸浮擷取按鈕", "收起畫面上的懸浮按鈕", R.drawable.ic_stop, null) {
                        startService(FloatingCaptureService.stopIntent(context))
                        status = "懸浮擷取按鈕已停用"
                    }
                    ActionButton("開啟截圖資料夾", "Pictures / Screenshot Capture", R.drawable.ic_folder, null) { openSystemScreenshotFolder() }
                    StatusCard(status)
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(72.dp)
                    .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Purple)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFFFF1F7)),
                contentAlignment = Alignment.Center
            ) { Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("螢幕擷取", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("一按就擷取目前畫面", fontSize = 15.sp, color = Muted)
            }
        }
    }

    @Composable
    private fun StepsCard() {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.88f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Step(R.drawable.ic_shield, Purple, "先授權", "允許顯示在其他 App 上層，並同意系統螢幕擷取。")
                Step(R.drawable.ic_touch, Pink, "再一按", "上方「擷取」存整個螢幕，下方「返回」回到本 App。")
                Step(R.drawable.ic_bolt, Amber, "自動隱藏", "拍攝瞬間三顆懸浮按鈕都會自動隱藏，畫面乾淨。")
            }
        }
    }

    @Composable
    private fun Step(icon: Int, tone: Color, title: String, detail: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tone.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tone) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(detail, fontSize = 14.sp, color = Muted)
            }
        }
    }

    @Composable
    private fun ActionButton(title: String, detail: String, icon: Int, brush: Brush?, onClick: () -> Unit) {
        val filled = brush != null
        val label = if (filled) Color.White else Ink
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(if (filled) 14.dp else 0.dp, RoundedCornerShape(22.dp), spotColor = Purple)
                .clip(RoundedCornerShape(22.dp))
                .then(
                    if (brush != null) Modifier.background(brush)
                    else Modifier.background(Color.White.copy(alpha = 0.9f)).border(1.dp, Purple.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (filled) Color.White.copy(alpha = 0.22f) else Purple.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) { Icon(painterResource(icon), null, Modifier.size(21.dp), tint = if (filled) Color.White else Purple) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = label)
                Text(detail, fontSize = 13.sp, color = if (filled) Color.White.copy(alpha = 0.82f) else Muted)
            }
        }
    }

    @Composable
    private fun StatusCard(status: String) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Purple.copy(alpha = 0.08f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp), tint = Purple)
            Text(status, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }

    private fun openSystemScreenshotFolder() {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Pictures/Screenshot Capture")
        startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) })
    }
    companion object { private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents" }
}
