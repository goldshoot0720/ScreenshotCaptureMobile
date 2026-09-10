package com.example.screencapture

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class TargetApp(val label: String, val packageName: String, val lastUsedAt: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CaptureScreen() }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun CaptureScreen() {
        val context = this
        var pickerOpen by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<TargetApp?>(null) }
        var status by remember { mutableStateOf("選取最近使用的 App") }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = selected
            if (result.resultCode == Activity.RESULT_OK && result.data != null && target != null) {
                status = "正在切換至 ${target.label}…"
                ContextCompat.startForegroundService(context, CaptureService.intent(context, result.resultCode, result.data!!))
                packageManager.getLaunchIntentForPackage(target.packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                status = "未取得系統螢幕擷取授權"
            }
        }

        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("螢幕擷取") }) }) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("從最近使用的 App 中選擇目標，再由 Android 的系統視窗授權擷取。", style = MaterialTheme.typography.bodyLarge)
                    Text(selected?.label ?: "尚未選擇 App", style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = {
                            if (hasUsageAccess()) pickerOpen = true
                            else {
                                status = "請在系統設定允許「使用情況存取」，才能顯示最近使用的 App"
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("選取最近使用的 App") }
                    Button(
                        onClick = {
                            if (selected == null) status = "請先選取最近使用的 App"
                            else permissionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = selected != null
                    ) { Text("擷取螢幕畫面") }
                    Button(onClick = { openSystemScreenshotFolder() }, modifier = Modifier.fillMaxWidth()) {
                        Text("直接開啟截圖資料夾")
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text("擷取期間會暫時將系統音效設為 0%，完成後立即還原。若裝置或地區強制相機快門聲，系統政策仍可能優先。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (pickerOpen) {
                AppPicker(recentApps(), { pickerOpen = false }) { app ->
                    selected = app; pickerOpen = false; status = "已選取 ${app.label}"
                }
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun recentApps(): List<TargetApp> {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(System.currentTimeMillis() - RECENT_WINDOW_MILLIS, System.currentTimeMillis())
        val latestUse = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != packageName) {
                latestUse[event.packageName] = event.timeStamp
            }
        }
        return latestUse.mapNotNull { (packageName, lastUsedAt) ->
            if (packageManager.getLaunchIntentForPackage(packageName) == null) null
            else runCatching {
                TargetApp(packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString(), packageName, lastUsedAt)
            }.getOrNull()
        }.sortedByDescending { it.lastUsedAt }
    }

    private fun openSystemScreenshotFolder() {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Pictures/Screenshot Capture")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivity(intent)
    }

    companion object {
        private const val RECENT_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
        private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"
    }
}

@androidx.compose.runtime.Composable
private fun AppPicker(apps: List<TargetApp>, onDismiss: () -> Unit, onPick: (TargetApp) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近使用的 App") },
        text = {
            if (apps.isEmpty()) Text("過去 24 小時沒有可擷取的最近使用 App。")
            else LazyColumn {
                items(apps) { app ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("取消") } }
    )
}