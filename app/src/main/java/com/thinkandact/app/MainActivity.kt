package com.thinkandact.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.thinkandact.app.ui.morning.MorningScreen
import com.thinkandact.app.ui.routine.RoutineScreen
import com.thinkandact.app.ui.theme.ThinkAndActTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThinkAndActTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "morning") {
                    composable("morning") {
                        MorningScreen(
                            onOpenRoutines = { navController.navigate("routines") }
                        )
                    }
                    composable("routines") {
                        RoutineScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
