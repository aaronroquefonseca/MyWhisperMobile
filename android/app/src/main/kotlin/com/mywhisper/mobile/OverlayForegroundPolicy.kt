package com.mywhisper.mobile

/** API-safe type masks; idle readiness must not request microphone access at boot. */
internal object OverlayForegroundPolicy {
    fun types(sdk: Int, microphone: Boolean): Int {
        val idle = if (sdk >= 34) 0x40000000 else 0 // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val capture = if (sdk >= 30 && microphone) 0x80 else 0 // FOREGROUND_SERVICE_TYPE_MICROPHONE
        return idle or capture
    }
}
