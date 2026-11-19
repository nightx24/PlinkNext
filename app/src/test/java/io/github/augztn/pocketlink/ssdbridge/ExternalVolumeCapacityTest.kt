package io.github.augztn.pocketlink.ssdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalVolumeCapacityTest {
    @Test
    fun extractsRemovableVolumeUuidFromDocumentId() {
        assertEquals("ABCD-1234", externalStorageVolumeId("ABCD-1234:"))
        assertEquals("ABCD-1234", externalStorageVolumeId("ABCD-1234:Android/data"))
    }

    @Test
    fun rejectsPrimaryAndMalformedDocumentIds() {
        assertNull(externalStorageVolumeId("primary:"))
        assertNull(externalStorageVolumeId(":folder"))
        assertNull(externalStorageVolumeId("ABCD-1234"))
    }

    @Test
    fun calculatesCapacityFromFilesystemByteCounts() {
        val capacity = externalVolumeCapacity(1_000L, 250L)!!

        assertEquals(1_000L, capacity.totalBytes)
        assertEquals(750L, capacity.usedBytes)
        assertEquals(250L, capacity.availableBytes)
        assertEquals(75.0, capacity.usagePercent, 0.001)
    }

    @Test
    fun rejectsUnavailableOrInconsistentFilesystemReadings() {
        assertNull(externalVolumeCapacity(0L, 0L))
        assertNull(externalVolumeCapacity(1_000L, -1L))
        assertNull(externalVolumeCapacity(1_000L, 1_001L))
    }
}
