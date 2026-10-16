package com.galaxy.assettrail.driver

import android.app.Application

class AssetTrailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
