package com.shanks.minify.build

import com.shanks.minify.ui.nav.MinifyTab
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Build / configuration smoke checks for the editor replacement (task 11.3).
 *
 * These are deterministic, device-free JVM unit tests that read the project's
 * Gradle and manifest configuration from source and assert the invariants the
 * integration must preserve. They lock down regressions in the reconciled build
 * so a later edit that (say) drops `RECORD_AUDIO`, re-adds a legacy launcher, or
 * un-pins a shared AndroidX dependency fails fast.
 *
 * Requirements covered:
 * - **10.6 / 11.2 (identity):** `applicationId` and `namespace` stay `com.shanks.minify`.
 * - **11.6 (permissions):** the merged manifest declares `RECORD_AUDIO`.
 * - **10.3 (activities / launcher):** `VideoEditingActivity` and `ErrorDisplayActivity`
 *   are contributed by `:videoeditor` with **no** legacy `LAUNCHER` entry; the app's
 *   `MainActivity` remains the sole launcher.
 * - **10.3 (tab order):** `MinifyTab.entries` is `[VIDEO, PHOTO]`.
 * - **11.2 (single-version pin):** shared transitive AndroidX / coroutines deps are
 *   pinned to a single version via a strict constraint.
 * - **11.3 (no-compatible-version):** an unreconcilable pin fails with a detailed
 *   message naming the conflicting dependencies (modelled by [reconcileStrictPin],
 *   which mirrors the documented Gradle strict-constraint semantics).
 */
class BuildConfigurationSmokeTest {

    private val projectRoot: File = findProjectRoot()
    private val appBuildGradle: String by lazy {
        projectRoot.resolve("app/build.gradle.kts").readText()
    }
    private val appManifest: String by lazy {
        projectRoot.resolve("app/src/main/AndroidManifest.xml").readText()
    }
    private val videoEditorManifest: String by lazy {
        projectRoot.resolve("videoeditor/src/main/AndroidManifest.xml").readText()
    }

    // --- Req 10.6 / 11.2: package identity is preserved ----------------------

    @Test
    fun applicationIdAndNamespaceRemainMinify() {
        assertAll(
            {
                assertTrue(
                    Regex("""namespace\s*=\s*"com\.shanks\.minify"""").containsMatchIn(appBuildGradle),
                    "app namespace must remain \"com.shanks.minify\"",
                )
            },
            {
                assertTrue(
                    Regex("""applicationId\s*=\s*"com\.shanks\.minify"""").containsMatchIn(appBuildGradle),
                    "app applicationId must remain \"com.shanks.minify\"",
                )
            },
        )
    }

    // --- Req 11.6: RECORD_AUDIO is declared for the integrated video editor ---

    @Test
    fun mergedManifestDeclaresRecordAudioPermission() {
        // Present in the app manifest, and also contributed by the :videoeditor
        // library manifest that merges into the app (com.shanks.minify).
        assertAll(
            {
                assertTrue(
                    appManifest.contains("android.permission.RECORD_AUDIO"),
                    "app manifest must declare RECORD_AUDIO for the integrated video editor (Req 11.6)",
                )
            },
            {
                assertTrue(
                    videoEditorManifest.contains("android.permission.RECORD_AUDIO"),
                    ":videoeditor manifest must declare RECORD_AUDIO (Req 11.6)",
                )
            },
        )
    }

    // --- Req 10.3: editor activities merge, with no legacy launcher ----------

    @Test
    fun videoEditorActivitiesMergeWithNoLegacyLauncher() {
        assertAll(
            {
                assertTrue(
                    videoEditorManifest.contains(".VideoEditingActivity"),
                    "merged manifest must contain VideoEditingActivity (Req 10.3)",
                )
            },
            {
                assertTrue(
                    videoEditorManifest.contains(".ErrorDisplayActivity"),
                    "merged manifest must contain ErrorDisplayActivity (Req 10.3)",
                )
            },
            {
                // The library contributes no launcher; LibreCuts' original
                // LAUNCHER MainActivity was removed (task 1.2).
                assertFalse(
                    videoEditorManifest.contains("android.intent.category.LAUNCHER"),
                    ":videoeditor must contribute NO legacy LAUNCHER entry (Req 10.3)",
                )
            },
            {
                assertFalse(
                    videoEditorManifest.contains(".MainActivity"),
                    ":videoeditor must not re-declare the legacy launcher MainActivity (Req 10.3)",
                )
            },
        )
    }

    @Test
    fun appMainActivityIsTheSoleLauncher() {
        // Exactly one LAUNCHER category, and it belongs to the app's MainActivity.
        val launcherCount =
            Regex("""android\.intent\.category\.LAUNCHER""").findAll(appManifest).count()
        assertEquals(
            1,
            launcherCount,
            "the app manifest must declare exactly one LAUNCHER entry (Req 10.3)",
        )
        assertTrue(
            appManifest.contains(".MainActivity"),
            "the sole launcher must be the app's MainActivity (Req 10.3)",
        )
    }

    // --- Req 10.3: fixed tab order Video / Photo ----------------------------

    @Test
    fun minifyTabOrderIsVideoPhoto() {
        assertEquals(
            listOf(MinifyTab.VIDEO, MinifyTab.PHOTO),
            MinifyTab.entries.toList(),
            "MinifyTab.entries order must be [VIDEO, PHOTO] (Req 10.3)",
        )
    }

    // --- Req 11.2: shared transitive deps pin to a single version ------------

    @Test
    fun sharedTransitiveDepsPinToASingleVersion() {
        // Each shared coordinate is constrained with strictly(<single version>),
        // so both media stacks and the compression pipeline resolve one version.
        val pinnedCoordinates = listOf(
            "androidx.core:core-ktx",
            "androidx.core:core",
            "androidx.appcompat:appcompat",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        )
        pinnedCoordinates.forEach { coord ->
            assertTrue(
                appBuildGradle.contains(coord),
                "shared dependency $coord must be pinned in app/build.gradle.kts (Req 11.2)",
            )
        }
        assertTrue(
            Regex("""version\s*\{\s*strictly\(""").containsMatchIn(appBuildGradle),
            "shared deps must use a strict single-version pin (Req 11.2)",
        )
        assertTrue(
            appBuildGradle.contains("androidx.lifecycle:"),
            "shared AndroidX lifecycle artifacts must be pinned (Req 11.2)",
        )
    }

    // --- Req 11.3: no-compatible-version fails with a detailed message -------

    @Test
    fun compatibleRequestResolvesAutomaticallyToThePin() {
        // A module that merely prefers/requires a different (non-strict) version
        // is coerced to the strict pin: the conflict resolves automatically to a
        // single compatible version (Req 11.2).
        val result = reconcileStrictPin(
            pinnedVersion = "1.19.0",
            requesters = listOf(
                VersionRequest(module = ":app", version = "1.19.0", strict = true),
                VersionRequest(module = ":videoeditor", version = "1.13.0", strict = false),
                VersionRequest(module = ":photoeditor", version = "1.13.1", strict = false),
            ),
        )
        assertTrue(result is ReconcileResult.Resolved, "compatible requests must resolve, was $result")
        assertEquals("1.19.0", (result as ReconcileResult.Resolved).version)
    }

    @Test
    fun noCompatibleVersionFailsWithDetailedMessage() {
        // Two modules that STRICTLY require incompatible versions cannot be
        // reconciled to a single version: the build must fail with a detailed
        // message naming every conflicting dependency requirement (Req 11.3).
        val result = reconcileStrictPin(
            pinnedVersion = "1.19.0",
            requesters = listOf(
                VersionRequest(module = ":app", version = "1.19.0", strict = true),
                VersionRequest(module = ":videoeditor", version = "1.13.0", strict = true),
            ),
        )
        assertTrue(result is ReconcileResult.Failed, "unreconcilable strict versions must fail, was $result")
        val message = (result as ReconcileResult.Failed).message
        assertAll(
            { assertTrue(message.contains(":app"), "message must name the :app requirement: $message") },
            {
                assertTrue(
                    message.contains(":videoeditor"),
                    "message must name the conflicting :videoeditor requirement: $message",
                )
            },
            { assertTrue(message.contains("1.19.0"), "message must list the pinned version: $message") },
            { assertTrue(message.contains("1.13.0"), "message must list the conflicting version: $message") },
            {
                assertTrue(
                    message.contains("manual", ignoreCase = true) ||
                        message.contains("resolve", ignoreCase = true),
                    "message must direct the maintainer to a manual resolution: $message",
                )
            },
        )
    }

    private companion object {

        /** Walk up from the working directory to the module root that owns `settings.gradle.kts`. */
        fun findProjectRoot(): File {
            val workingDir = System.getProperty("user.dir") ?: "."
            var dir: File? = File(workingDir).absoluteFile
            while (dir != null) {
                if (dir.resolve("settings.gradle.kts").isFile &&
                    dir.resolve("app/build.gradle.kts").isFile
                ) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("Could not locate the Minify project root from ${System.getProperty("user.dir")}")
        }
    }
}

/**
 * A single module's version requirement for a shared coordinate.
 *
 * @param strict `true` mirrors Gradle's `version { strictly(...) }` (an
 *   inflexible pin); `false` is an ordinary prefer/require that can be coerced.
 */
data class VersionRequest(val module: String, val version: String, val strict: Boolean)

/** Outcome of reconciling a strict pin against a set of module requirements. */
sealed interface ReconcileResult {
    data class Resolved(val version: String) : ReconcileResult
    data class Failed(val message: String) : ReconcileResult
}

/**
 * Pure specification model of the strict-pin reconciliation documented in
 * `app/build.gradle.kts` (Req 11.2 / 11.3).
 *
 * Non-strict requesters are coerced to [pinnedVersion] (the conflict resolves
 * automatically to a single compatible version, Req 11.2). If two or more
 * requesters *strictly* demand different versions, no single version can build
 * and the reconciliation fails with a detailed message listing every
 * conflicting requirement and directing the maintainer to a manual decision
 * (Req 11.3).
 */
fun reconcileStrictPin(
    pinnedVersion: String,
    requesters: List<VersionRequest>,
): ReconcileResult {
    val strictVersions = buildSet {
        add(pinnedVersion)
        requesters.filter { it.strict }.forEach { add(it.version) }
    }
    if (strictVersions.size <= 1) {
        return ReconcileResult.Resolved(pinnedVersion)
    }
    val conflicts = (
        listOf("strict pin -> $pinnedVersion") +
            requesters.filter { it.strict }.map { "${it.module} -> ${it.version} (strict)" }
        ).joinToString(separator = ", ")
    return ReconcileResult.Failed(
        "No single compatible version for shared dependency: conflicting requirements [$conflicts]. " +
            "Requires a manual maintainer decision (exclude or substitute) to resolve.",
    )
}
