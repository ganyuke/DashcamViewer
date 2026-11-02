package org.nextcolor.dashcamviewer

import org.junit.Assert.*
import org.junit.Test
import org.nextcolor.dashcamviewer.android.CameraWifiCredentials

class CameraWifiCredentialsTest {
    @Test
    fun connectionAcceptsNormalWifiPasswordsIncludingSpacesAndPunctuation() {
        listOf("abc! @#$", "camera pass phrase", "a".repeat(63)).forEach {
            assertNull(CameraWifiCredentials.passwordError(it))
        }
    }

    @Test
    fun invalidLengthsAndNonPrintableCharactersHaveAnExplanation() {
        listOf("short", "a".repeat(64), "abc\ndefgh", "pässword").forEach {
            assertNotNull(CameraWifiCredentials.passwordError(it))
        }
    }

    @Test
    fun ssidLimitCountsUtf8BytesAndDoesNotTrimMeaningfulSpaces() {
        assertNull(CameraWifiCredentials.ssidError(" Camera "))
        assertNull(CameraWifiCredentials.ssidError("é".repeat(16)))
        assertNotNull(CameraWifiCredentials.ssidError("é".repeat(17)))
        assertNotNull(CameraWifiCredentials.ssidError(""))
    }
}
