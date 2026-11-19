package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object SampleMediaSeeder {
    private const val PREFS_NAME = "ssd-bridge"
    private const val KEY_SEEDED = "sample_media_seeded_v1"

    fun resolveStorageRoot(context: Context): File {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (granted) return Environment.getExternalStorageDirectory().canonicalFile
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "PocketLinkStorage")
            .apply { mkdirs() }
            .canonicalFile
    }

    fun seedIfNeeded(context: Context, force: Boolean = false): Int {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_SEEDED, false)) {
            return 0
        }

        val root = resolveStorageRoot(appContext)
        val categories = listOf("Music", "Videos", "Pictures", "Documents")
        var copied = 0
        for (folder in categories) {
            val assetDir = "sample_media/$folder"
            val names = runCatching { appContext.assets.list(assetDir)?.toList().orEmpty() }
                .getOrDefault(emptyList())
            if (names.isEmpty()) continue

            val targetDir = File(root, folder).apply { mkdirs() }
            for (name in names) {
                val dest = File(targetDir, name)
                if (!force && dest.exists() && dest.length() > 0L) continue
                runCatching {
                    appContext.assets.open("$assetDir/$name").use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    copied++
                }
            }
        }

        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        if (copied > 0) {
            BridgeService.refreshMediaIndex()
        }
        return copied
    }
}
