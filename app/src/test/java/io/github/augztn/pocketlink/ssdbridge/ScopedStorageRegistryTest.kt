package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScopedStorageRegistryTest {

    @Test
    fun `normalizePath trims slashes and backslashes`() {
        assertEquals("Download/Packs", ScopedStorageRegistry.normalizePath("\\Download//Packs/"))
        assertEquals("", ScopedStorageRegistry.normalizePath("/"))
    }

    @Test
    fun `picker intent targets primary folder via SAF without full permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ScopedStorageRegistry(context)

        val intent = registry.createScopedFolderPickerIntent("Music")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        val initialUri = IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)
        assertTrue(initialUri.toString().contains("primary%3AMusic") || initialUri.toString().contains("primary:Music"))
    }

    @Test
    fun `scoped folder statuses include standard MT-style internal folders`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ScopedStorageRegistry(context)
        val statuses = registry.getScopedFolderStatuses()
        val paths = statuses.map { it.mountPath }.toSet()

        assertTrue("Music" in paths)
        assertTrue("Movies" in paths)
        assertTrue("Pictures" in paths)
        assertTrue("DCIM" in paths)
        assertTrue("Download" in paths)
        assertTrue("Documents" in paths)
        assertTrue("Android/data" in paths)
    }
}
