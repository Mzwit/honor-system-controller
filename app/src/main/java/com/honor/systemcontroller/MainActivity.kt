package com.honor.systemcontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.honor.systemcontroller.core.privilege.ShizukuState
import com.honor.systemcontroller.ui.HonorRoot
import com.honor.systemcontroller.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HonorRoot(vm) }
    }

    override fun onResume() {
        super.onResume()
        ShizukuState.refresh()
        vm.onResume()
    }
}
