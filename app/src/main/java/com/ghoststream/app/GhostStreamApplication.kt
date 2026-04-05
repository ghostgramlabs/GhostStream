package com.ghostgramlabs.directserve

import android.app.Application
import com.ghostgramlabs.directserve.state.AppContainer

class GhostStreamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
