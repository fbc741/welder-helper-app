package com.welder.helper

import android.app.Application
import android.content.Context

class WelderApp : Application() {
    companion object {
        lateinit var instance: WelderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
