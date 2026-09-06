package com.cropcast.app

import android.os.Bundle
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cropcast.app.data.FirebaseSensorRepository
import com.cropcast.app.ui.CropCastApp
import com.cropcast.app.ui.CropCastViewModel
import com.cropcast.app.ui.CropCastViewModelFactory
import com.cropcast.app.ui.theme.CropCastTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val forceDemo = isDebuggable && intent.getBooleanExtra(EXTRA_FORCE_DEMO, false)
        val demoCrop = if (isDebuggable) intent.getStringExtra(EXTRA_DEMO_CROP) ?: "Potato" else "Potato"
        splashScreen.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction(provider::remove)
                .start()
        }
        enableEdgeToEdge()
        setContent {
            CropCastTheme {
                val viewModel: CropCastViewModel = viewModel(
                    factory = CropCastViewModelFactory(
                        FirebaseSensorRepository(applicationContext, forceDemo = forceDemo),
                        demoCrop = demoCrop
                    )
                )
                CropCastApp(viewModel)
            }
        }
    }

    private companion object {
        const val EXTRA_FORCE_DEMO = "forceDemo"
        const val EXTRA_DEMO_CROP = "demoCrop"
    }
}
