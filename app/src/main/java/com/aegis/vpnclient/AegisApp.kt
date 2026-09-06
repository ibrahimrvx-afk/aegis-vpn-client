package com.aegis.vpnclient

import android.app.Application
import libv2ray.Libv2ray

class AegisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
    }
}
