package io.github.madooroy.pinionkaroo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The version is written in three places: app/build.gradle.kts, the KarooExtension constructor and
 * app/manifest.json (the update manifest attached to each GitHub release, which Karoo compares with the installed
 * version). This keeps them in step. Unit tests run with the app module as working directory.
 */
class ReleaseVersionTest {
    private fun find(file: String, regex: String): String {
        val text = File(file).readText()
        return Regex(regex).find(text)?.groupValues?.get(1) ?: error("No match for $regex in $file")
    }

    @Test
    fun versionsAgree() {
        val name = find("build.gradle.kts", """versionName\s*=\s*"([^"]+)"""")
        val code = find("build.gradle.kts", """versionCode\s*=\s*(\d+)""")
        val service = find(
            "src/main/kotlin/io/github/madooroy/pinionkaroo/extension/PinionExtensionService.kt",
            """KarooExtension\(EXTENSION_ID,\s*"([^"]+)"\)""",
        )
        assertEquals("KarooExtension version", name, service)
        assertEquals("manifest.json latestVersion", name, find("manifest.json", """"latestVersion"\s*:\s*"([^"]+)""""))
        assertEquals("manifest.json latestVersionCode", code, find("manifest.json", """"latestVersionCode"\s*:\s*(\d+)"""))
        val apkUrl = find("manifest.json", """"latestApkUrl"\s*:\s*"([^"]+)"""")
        assertEquals(
            "manifest.json latestApkUrl",
            "https://github.com/madooroy/pinion-karoo-extension/releases/download/v$name/pinion-karoo-extension-$name.apk",
            apkUrl,
        )
    }
}
