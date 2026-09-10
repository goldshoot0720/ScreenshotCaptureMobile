package com.example.screencapture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import java.text.DateFormat
import java.util.Date

data class TargetApp(val label: String, val packageName: String)
data class ScreenshotItem(val uri: Uri, val name: String, val createdAt: Long)

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
        var folderOpen by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<TargetApp?>(null) }
        var status by remember { mutableStateOf("選取要擷取的 App") }
        val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val folderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) folderOpen = true else status = "需要圖片讀取權限才能開啟截圖資料夾"
        }
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
                    Text("先選擇目標 App，再由 Android 的系統視窗授權擷取。", style = MaterialTheme.typography.bodyLarge)
                    Text(selected?.label ?: "尚未選擇 App", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("選取 App") }
                    Button(
                        onClick = {
                            if (selected == null) status = "請先選取目標 App"
                            else permissionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selected != null
                    ) { Text("擷取螢幕畫面") }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, imagePermission) == PackageManager.PERMISSION_GRANTED) folderOpen = true
                            else folderPermissionLauncher.launch(imagePermission)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("開啟截圖資料夾") }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text("擷取期間會暫時將系統音效設為 0%，完成後立即還原。若裝置或地區強制相機快門聲，系統政策仍可能優先。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (pickerOpen) {
                AppPicker(launchableApps(), { pickerOpen = false }) { app ->
                    selected = app; pickerOpen = false; status = "已選取 ${app.label}"
                }
            }
            if (folderOpen) ScreenshotFolder(screenshots(), { folderOpen = false }) { openScreenshot(it.uri) }
        }
    }

    private fun launchableApps(): List<TargetApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .map { TargetApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != packageName }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun screenshots(): List<ScreenshotItem> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        return contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/Screenshot Capture/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val date = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            buildList {
                while (cursor.moveToNext()) {
                    add(ScreenshotItem(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id).toString()), cursor.getString(name), cursor.getLong(date) * 1000))
                }
            }
        } ?: emptyList()
    }

    private fun openScreenshot(uri: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

@androidx.compose.runtime.Composable
private fun AppPicker(apps: List<TargetApp>, onDismiss: () -> Unit, onPick: (TargetApp) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選取 App") },
        text = {
            LazyColumn {
                items(apps) { app ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, modifier = Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("取消") } }
    )
}

@androidx.compose.runtime.Composable
private fun ScreenshotFolder(screenshots: List<ScreenshotItem>, onDismiss: () -> Unit, onOpen: (ScreenshotItem) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("截圖資料夾") },
        text = {
            if (screenshots.isEmpty()) Text("尚未儲存任何截圖。")
            else LazyColumn {
                items(screenshots) { screenshot ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(screenshot) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(screenshot.name)
                            Text(DateFormat.getDateTimeInstance().format(Date(screenshot.createdAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("開啟", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("關閉") } }
    )
}