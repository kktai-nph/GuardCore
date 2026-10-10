package com.parentalcontrol.parent

import android.app.Application
import com.parentalcontrol.core.CrtNativeLoader

class ParentApplication : Application() {
    companion object {
        lateinit var instance: ParentApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrtNativeLoader.load()
    }
}
