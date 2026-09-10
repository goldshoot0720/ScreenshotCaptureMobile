package com.example.screencapture

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CaptureHome() }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun CaptureHome() {
        var helpVisible by remember { mutableStateOf(false) }
        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("螢幕擷取") }) }) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("從系統最近任務選擇目標 App", style = MaterialTheme.typography.headlineSmall)
                    Text("本 App 不讀取或複製你的最近任務清單。請在 One UI 的系統最近任務中選擇仍在後台、可切回前台的 App，再使用快速設定按鈕擷取。", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { helpVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("查看擷取步驟") }
                    Button(onClick = { openSystemScreenshotFolder() }, modifier = Modifier.fillMaxWidth()) { Text("直接開啟截圖資料夾") }
                    if (helpVisible) CaptureSteps { helpVisible = false }
                }
            }
        }
    }

    private fun openSystemScreenshotFolder() {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:Pictures/Screenshot Capture")
        startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }

    companion object { private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents" }
}

@androidx.compose.runtime.Composable
private fun CaptureSteps(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("擷取步驟") },
        text = { Text("1. 在系統最近任務中點選目標 App。\n\n2. 下拉通知列並開啟快速設定。\n\n3. 點選「擷取目前畫面」。第一次使用時，請先在快速設定的編輯頁加入此按鈕。\n\n4. 在 Android 系統授權後，App 會回到目標畫面並儲存截圖。") },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } }
    )
}