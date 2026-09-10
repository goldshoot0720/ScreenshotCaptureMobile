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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { CaptureHome() } }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun CaptureHome() {
        val context = this
        var status by remember { mutableStateOf("啟用後，先切換至目標 App，再點粉色懸浮按鈕。") }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ContextCompat.startForegroundService(context, FloatingCaptureService.startIntent(context, result.resultCode, result.data!!))
                status = "懸浮擷取按鈕已啟用"
            } else status = "未取得 Android 系統螢幕擷取授權"
        }
        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("螢幕擷取") }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("一按就擷取目前畫面", style = MaterialTheme.typography.headlineSmall)
                    Text("這是本 App 的懸浮按鈕。拍攝瞬間它會自動隱藏，因此不會出現在截圖中。", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = {
                        if (Settings.canDrawOverlays(context)) projectionLauncher.launch(manager.createScreenCaptureIntent())
                        else { status = "請允許「顯示在其他 App 上層」後，再點一次啟用。"; startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("啟用懸浮擷取按鈕") }
                    Button(onClick = { startService(FloatingCaptureService.stopIntent(context)); status = "懸浮擷取按鈕已停用" }, modifier = Modifier.fillMaxWidth()) { Text("停用懸浮擷取按鈕") }
                    Button(onClick = { openSystemScreenshotFolder() }, modifier = Modifier.fillMaxWidth()) { Text("直接開啟截圖資料夾") }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    private fun openSystemScreenshotFolder() {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Pictures/Screenshot Capture")
        startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) })
    }
    companion object { private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents" }
}