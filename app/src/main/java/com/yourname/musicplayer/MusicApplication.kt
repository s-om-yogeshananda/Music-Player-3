package com.yourname.musicplayer

import android.app.Application

class MusicApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startObserving()
    }
}
