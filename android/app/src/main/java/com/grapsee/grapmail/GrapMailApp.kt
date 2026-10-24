package com.grapsee.grapmail

import android.app.Application
import com.grapsee.grapmail.data.DeviceId

class GrapMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceId.init(this)
    }
}
