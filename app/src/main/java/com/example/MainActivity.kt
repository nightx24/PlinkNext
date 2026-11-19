package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.PocketLinkApp
import com.example.ui.PocketLinkViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PocketLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketLinkApp(viewModel = viewModel)
        }
    }
}
