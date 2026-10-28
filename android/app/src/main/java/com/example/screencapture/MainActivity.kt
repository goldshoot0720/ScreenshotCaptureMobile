package com.example.screencapture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CaptureHome() }
    }

    @Composable
    private fun CaptureHome() {
        val context = this
        val samsung = remember { SamsungScrollCapture.isSamsung(context) }
        var status by remember { mutableStateOf("啟用後可在任何畫面截圖。長截圖交給三星內建捲動擷取，不用開啟 TalkBack。") }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ContextCompat.startForegroundService(context, FloatingCaptureService.startIntent(context, result.resultCode, result.data!!))
                status = "懸浮擷取按鈕已啟用"
            } else status = "未取得 Android 系統螢幕擷取授權"
        }
        val requestProjection = { projectionLauncher.launch(manager.createScreenCaptureIntent()) }
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }
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
                    ActionButton(
                        "開啟三星截圖設定",
                        "開啟「截圖後顯示工具列」。長截圖不用 TalkBack。",
                        R.drawable.ic_settings,
                        null
                    ) {
                        status = when (SamsungScrollCapture.openSettings(context)) {
                            SamsungScrollCapture.SettingsTarget.SAMSUNG -> "請確認「截圖後顯示工具列」已開啟。"
                            SamsungScrollCapture.SettingsTarget.SYSTEM -> "請到 進階功能 > 截圖與螢幕錄影，開啟「截圖後顯示工具列」。"
                        }
                    }
                    ActionButton("啟用懸浮擷取按鈕", "授權後在任何畫面顯示五個按鈕", R.drawable.ic_capture_shutter, CaptureBrush) {
                        if (!Settings.canDrawOverlays(context)) {
                            status = "請允許「顯示在其他 App 上層」後，再點一次啟用。"
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        } else if (needsNotificationConsent()) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else requestProjection()
                    }
                    ActionButton("停用懸浮擷取按鈕", "收起畫面上的懸浮按鈕", R.drawable.ic_mute, null) {
                        startService(FloatingCaptureService.stopIntent(context))
                        status = "懸浮擷取按鈕已停用"
                    }
                    ActionButton("開啟截圖資料夾", "Pictures / Screenshot Capture", R.drawable.ic_saved, null) { openSystemScreenshotFolder() }
                    StatusCard(
                        if (samsung) "長截圖使用三星內建捲動擷取，不用開啟 TalkBack。"
                        else "這支手機沒有三星內建長截圖，截圖仍可使用。"
                    )
                    StatusCard(status)
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painterResource(R.drawable.app_logo),
                null,
                Modifier
                    .size(72.dp)
                    .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Purple)
                    .clip(RoundedCornerShape(24.dp))
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("螢幕擷取", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("截圖、三星長截圖，不用開啟 TalkBack", fontSize = 15.sp, color = Muted)
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
                Step(R.drawable.ic_permission, Purple, "先授權", "允許顯示在其他 App 上層，並同意系統螢幕擷取。請在三星設定開啟「截圖後顯示工具列」。不用開啟 TalkBack。")
                Step(R.drawable.ic_capture_shutter, Pink, "截圖", "存下目前整個畫面。")
                Step(R.drawable.ic_long_single, Purple, "長截圖（一張圖）", "先藏起懸浮按鈕，再交給三星內建長截圖。按音量下鍵＋電源鍵，長按工具列向下箭頭，一路捲到最底。")
                Step(R.drawable.ic_long_multi, Amber, "長截圖（多張圖）", "同樣交給三星。按音量下鍵＋電源鍵後，逐次點工具列的向下箭頭，每一段各延伸一次。")
                Step(R.drawable.ic_settings, Purple, "懸浮列", "每個按鈕都有文字。按住可拖到旁邊。長截圖會先把按鈕藏起來，通知裡可再顯示。設定回到這裡，關閉 APP 會結束程式。")
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
            ) { Image(painterResource(icon), null, Modifier.size(26.dp)) }
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
            ) { Image(painterResource(icon), null, Modifier.size(26.dp)) }
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
            Image(painterResource(R.drawable.ic_info), null, Modifier.size(22.dp))
            Text(status, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }

    /** The capture service runs in the foreground, so Android 13+ needs the notification to be allowed. */
    private fun needsNotificationConsent() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun openSystemScreenshotFolder() {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Pictures/Screenshot Capture")
        startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) })
    }
    companion object { private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents" }
}
