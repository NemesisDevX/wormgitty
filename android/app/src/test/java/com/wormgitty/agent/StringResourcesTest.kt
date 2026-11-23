package com.wormgitty.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * String-resource invariants enforced without a device:
 *  1. Every key in main/strings.xml MUST be overridden in worm — the worm
 *     flavor is English-facing and must never inherit the Arabic fallback.
 *  2. No dead keys: every declared string must be referenced from Kotlin
 *     (R.string.x / getString), a manifest, or res/xml (@string/x).
 */
class StringResourcesTest {

    private val moduleDir: File by lazy {
        // Unit tests run with CWD = the module dir; fall back when launched
        // from the repo root.
        listOf(File("."), File("android/app"))
            .first { File(it, "src/main/res/values/strings.xml").exists() }
    }

    private fun keysOf(stringsFile: File): Set<String> =
        Regex("""<string name="([^"]+)">""")
            .findAll(stringsFile.readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun stringsFile(flavor: String) =
        File(moduleDir, "src/$flavor/res/values/strings.xml")

    @Test
    fun wormOverridesEveryMainKey() {
        val main = keysOf(stringsFile("main"))
        val worm = keysOf(stringsFile("worm"))
        val missing = main - worm
        assertTrue(
            "worm must override every main key; missing: ${missing.sorted()}",
            missing.isEmpty()
        )
    }

    @Test
    fun noDeadStringKeys() {
        val flavors = listOf("main", "worm", "ghost", "master")
        val declared = flavors.flatMap { keysOf(stringsFile(it)) }.toSet()

        // Everything that can reference a string: Kotlin, manifests, res/xml.
        // strings.xml files are included harmlessly — they hold name="key",
        // never R.string.key / @string/key.
        val corpus = StringBuilder()
        moduleDir.resolve("src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { corpus.append(it.readText()).append('\n') }
        val haystack = corpus.toString()

        val dead = declared.filter { key ->
            !haystack.contains("R.string.$key") && !haystack.contains("@string/$key")
        }
        assertTrue("dead string keys: ${dead.sorted()}", dead.isEmpty())
    }

    @Test
    fun gateKeysAllHaveImpactText() {
        PermKey.GATE_KEYS.forEach { k ->
            if (k.gateImpactRes == 0) fail("gate key $k must declare gateImpactRes")
        }
        assertTrue(PermKey.GATE_KEYS.size == 4)
    }
}
