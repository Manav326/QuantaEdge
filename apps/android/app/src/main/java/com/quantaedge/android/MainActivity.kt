package com.quantaedge.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.ui.QuantaEdgeApp
import com.quantaedge.android.ui.QuantaEdgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        ApiClient.initialize(applicationContext)
        setContent { QuantaEdgeTheme { QuantaEdgeApp() } }
    }
}
