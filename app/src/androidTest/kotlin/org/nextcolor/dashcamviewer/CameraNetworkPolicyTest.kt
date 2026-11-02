package org.nextcolor.dashcamviewer

import android.security.NetworkSecurityPolicy
import org.junit.Assert.*
import org.junit.Test
import org.nextcolor.dashcamviewer.android.A13CameraAddress

class CameraNetworkPolicyTest {
    @Test
    fun cameraHttpIsAllowedWithoutAllowingArbitraryCleartextHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted(A13CameraAddress.HOST))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }
}
