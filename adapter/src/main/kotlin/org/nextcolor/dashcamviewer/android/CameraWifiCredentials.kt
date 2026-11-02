package org.nextcolor.dashcamviewer.android

/** Validation for joining Wi-Fi; separate from the camera firmware's restricted settings API. */
object CameraWifiCredentials {
    fun ssidError(ssid: String): String? =
        when {
            ssid.isEmpty() -> "Enter or choose the camera’s Wi-Fi name."
            ssid.toByteArray(Charsets.UTF_8).size > 32 ||
                ssid.any { it.code < 32 || it.code == 127 } ->
                "This Wi-Fi name is too long or contains unsupported characters."
            else -> null
        }

    fun passwordError(password: String): String? =
        when {
            password.length !in 8..63 -> "Use a password with 8–63 characters."
            password.any { it.code !in 32..126 } ->
                "Use letters, numbers, spaces or standard keyboard symbols."
            else -> null
        }
}
