package com.thinkandact.app

import android.app.Application
import com.thinkandact.core.di.platformModule
import com.thinkandact.core.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class ThinkAndActApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ThinkAndActApplication)
            androidLogger()
            modules(sharedModule, platformModule())
        }
    }
}
