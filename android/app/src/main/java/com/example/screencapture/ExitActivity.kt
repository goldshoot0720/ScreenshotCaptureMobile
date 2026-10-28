package com.example.screencapture

import android.app.Activity
import android.os.Bundle

/** Clears this app's task. The floating panel starts it when the user taps 關閉APP. */
class ExitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishAndRemoveTask()
    }
}
