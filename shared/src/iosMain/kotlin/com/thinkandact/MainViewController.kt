package com.thinkandact

import androidx.compose.ui.window.ComposeUIViewController
import com.thinkandact.core.di.platformModule
import com.thinkandact.core.di.sharedModule
import org.koin.core.context.startKoin

fun MainViewController() = ComposeUIViewController { App() }

fun setupKoin() {
    startKoin {
        modules(sharedModule, platformModule())
    }
}
