package com.ghoststream.app

import android.app.Application
import com.ghoststream.app.state.AppContainer

class GhostStreamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

