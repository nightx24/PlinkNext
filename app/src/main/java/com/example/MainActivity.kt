package com.example

import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.store.AppLogger
import com.example.ui.PocketLinkApp
import com.example.ui.PocketLinkViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PocketLinkViewModel by viewModels()
    private var systemBackObserver: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 36) {
            val observer = OnBackInvokedCallback {
                AppLogger.info("System predictive back invoked at root task")
            }
            systemBackObserver = observer
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER,
                observer,
            )
        }
        setContent {
            PocketLinkApp(viewModel = viewModel)
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 36) {
            systemBackObserver?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            systemBackObserver = null
        }
        super.onDestroy()
    }
}
