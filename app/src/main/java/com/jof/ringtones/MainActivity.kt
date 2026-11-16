package com.jof.ringtones

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jof.ringtones.ui.RingtoneScreen
import com.jof.ringtones.ui.RingtonesViewModel
import com.jof.ringtones.ui.theme.RingtonesTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RingtonesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RingtonesTheme {
                RingtoneScreen(viewModel)
            }
        }
    }
}
