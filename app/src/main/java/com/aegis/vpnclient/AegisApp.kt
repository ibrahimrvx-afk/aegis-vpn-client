package com.aegis.vpnclient

import android.app.Application
import libv2ray.Libv2ray

class AegisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required once before any CoreController.startLoop() call.
        // envPath should point at a directory containing geoip.dat and
        // geosite.dat if any package uses "geosite:xxx" domain rules
        // (see Package.allowedDomains) — without these files present,
        // geosite-based rules silently match nothing. Extract them from
        // assets/ to filesDir on first run if you bundle them; not done
        // here since the actual .dat files aren't included in this
        // template (download from a source you trust, e.g.
        // https://github.com/Loyalsoldier/v2ray-rules-dat).
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
    }
}
