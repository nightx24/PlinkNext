package io.github.augztn.pocketlink.ssdbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalVolumeIdentityTest {
    @Test fun prefersUuidAndNormalizesCase() {
        assertEquals("uuid:abcd-1234", externalVolumeIdentity("ABCD-1234", "/storage/ABCD-1234", "USB"))
    }
    @Test fun usesMountPathWhenUuidIsUnavailable() {
        assertEquals("path:/storage/1234-5678", externalVolumeIdentity(null, "/storage/1234-5678", "USB"))
    }
    @Test fun fallsBackToDescriptionWithoutEnumerationIndex() {
        assertEquals("description:usb drive", externalVolumeIdentity(null, null, " USB Drive "))
    }
    @Test fun createsStableStorageManagerVolumeKey() {
        assertEquals("usb-ad3ef737f718aec6", externalVolumeKey("uuid:abcd"))
    }
}
