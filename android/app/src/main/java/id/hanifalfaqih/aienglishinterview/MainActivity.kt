package id.hanifalfaqih.aienglishinterview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import id.hanifalfaqih.aienglishinterview.core.monetization.RevenueCatConfig
import id.hanifalfaqih.aienglishinterview.navigation.AppNavHost
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configures RevenueCat when a public SDK key is supplied; otherwise
        // the paywall reports unavailable and the app works without it.
        RevenueCatConfig.init(this)
        enableEdgeToEdge()
        setContent {
            AIEnglishInterviewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavHost(
                        navController = rememberNavController(),
                        modifier = Modifier.padding(innerPadding),
                        // DEBUG-only entry point (see DebugVerificationActivity
                        // in src/debug): null in all production flows.
                        startDestination = intent.getStringExtra(
                            "debug_start_destination",
                        ),
                    )
                }
            }
        }
    }
}
