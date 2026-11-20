package com.grapsee.grapmail.data

import android.content.Context
import java.util.UUID

/**
 * Stable per-install identifier sent as `x-device-id` — the header the account
 * API counts accounts against per device. Random UUID, persisted locally,
 * never derived from anything personal.
 */
object DeviceId {
    lateinit var value: String
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("grapmail_device", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (existing != null) { value = existing; return }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        value = generated
    }
}
