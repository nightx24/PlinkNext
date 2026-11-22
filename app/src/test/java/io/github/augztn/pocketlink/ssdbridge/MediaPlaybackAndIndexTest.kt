package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.media.AudioPlaybackController
import com.example.media.RepeatModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaPlaybackAndIndexTest {

    @Test
    fun mediaIndexDatabase_enablesWriteAheadLogging() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = MediaIndexDatabase(context)
        try {
            val db = dbHelper.readableDatabase
            assertTrue(db.isWriteAheadLoggingEnabled)
        } finally {
            dbHelper.close()
        }
    }

    @Test
    fun mediaPlaybackBridge_defaultsStoppedBeforeServiceStarts() {
        assertFalse(MediaPlaybackBridge.isServiceRunning)
    }

    @Test
    fun mediaPlaybackService_createsAndReleasesMediaSessionLifecycle() {
        val controller = Robolectric.buildService(MediaPlaybackService::class.java).create()
        try {
            assertTrue(MediaPlaybackBridge.isServiceRunning)
            assertFalse(MediaPlaybackBridge.isPlaying)
        } finally {
            controller.destroy()
        }
        assertFalse(MediaPlaybackBridge.isServiceRunning)
        assertFalse(MediaPlaybackBridge.isPlaying)
    }

    @Test
    fun audioPlaybackController_updatesModeAndSpeedStatesCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioController = AudioPlaybackController(context)
        try {
            assertFalse(audioController.state.value.shuffleEnabled)
            assertEquals(RepeatModeState.OFF, audioController.state.value.repeatMode)
            assertEquals(1.0f, audioController.state.value.speed)

            audioController.toggleShuffle()
            assertTrue(audioController.state.value.shuffleEnabled)

            audioController.cycleRepeatMode()
            assertEquals(RepeatModeState.ALL, audioController.state.value.repeatMode)

            audioController.cycleRepeatMode()
            assertEquals(RepeatModeState.ONE, audioController.state.value.repeatMode)

            audioController.setSpeed(1.5f)
            assertEquals(1.5f, audioController.state.value.speed)
        } finally {
            audioController.releaseController()
        }
    }
}
