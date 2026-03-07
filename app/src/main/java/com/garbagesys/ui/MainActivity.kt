package com.garbagesys.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garbagesys.ui.screens.*
import com.garbagesys.ui.theme.GarbageSysTheme
import com.garbagesys.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GarbageSysTheme {
                val vm: MainViewModel = viewModel()
                val setupState by vm.setupState.collectAsState()

                if (setupState.isInitialized) {
                    MainNavigation(vm)
                } else {
                    SetupFlow(vm)
                }
            }
        }
    }
}
