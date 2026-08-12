package com.vazheyar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vazheyar.app.ui.VazheYarRoot
import com.vazheyar.app.ui.VazheYarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VazheYarTheme {
                val vm: MainViewModel = viewModel()
                VazheYarRoot(vm)
            }
        }
    }
}
