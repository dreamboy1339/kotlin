/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType
import org.jetbrains.kotlin.gradle.testbase.*
import org.junit.jupiter.api.DisplayName
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DisplayName("Incremental compilation tests for Kotlin JS IR backend")
@JsGradlePluginTests
class Kotlin2JsIrBeIncrementalCompilationIT : KGPBaseTest() {
    override val defaultBuildOptions = BuildOptions(
        jsOptions = BuildOptions.JsOptions(
            jsCompilerType = KotlinJsCompilerType.IR,
            incrementalJsKlib = true,
            incrementalJsIr = true
        )
    )

    @DisplayName("Test rebuild after backend error")
    @GradleTest
    fun testRebuildAfterError(gradleVersion: GradleVersion) {
        project("kotlin-js-ir-ic-rebuild-after-error", gradleVersion) {
            fun readCacheFiles(): Map<String, Int> {
                val cacheFiles = mutableMapOf<String, Int>()
                projectPath.resolve("app/build/klib/cache/").toFile().walk().forEach { cachedFile ->
                    if (cachedFile.isFile) {
                        cacheFiles[cachedFile.absolutePath] = cachedFile.readBytes().contentHashCode()
                    }
                }
                return cacheFiles
            }

            val srcFile = projectPath.resolve("app/src/main/kotlin/App.kt").toFile()
            val badCode = srcFile.readText()

            var successfulBuildCacheFiles = emptyMap<String, Int>()
            srcFile.appendText("\nfun unknownFunction() = 1\n")
            build("nodeDevelopmentRun") {
                assertTasksExecuted(":app:compileDevelopmentExecutableKotlinJs")
                assertOutputContains("Hello, World!")
                successfulBuildCacheFiles = readCacheFiles()
                assertTrue("Cache should not be empty after successful build") { successfulBuildCacheFiles.isNotEmpty() }
            }

            srcFile.writeText(badCode)
            for (i in 0..1) {
                buildAndFail("nodeDevelopmentRun") {
                    assertTasksFailed(":app:compileDevelopmentExecutableKotlinJs")
                    val failedBuildCacheFiles = readCacheFiles()
                    assertEquals(successfulBuildCacheFiles, failedBuildCacheFiles, "The cache files should not be modified")
                }
            }

            srcFile.writeText(badCode.replace("Hello, World!", "Hello, Kotlin!") + "\nfun unknownFunction() = 2\n")
            build("nodeDevelopmentRun") {
                assertTasksExecuted(":app:compileDevelopmentExecutableKotlinJs")
                assertOutputContains("Hello, Kotlin!")
                val successfulRebuildCacheFiles = readCacheFiles()
                assertEquals(successfulBuildCacheFiles.size, successfulRebuildCacheFiles.size, "The number of files must be the same")
                assertNotEquals(successfulBuildCacheFiles, successfulRebuildCacheFiles, "The cache files should be modified")
            }
        }
    }

    @DisplayName("Test cache invalidation after modifying compiler args")
    @GradleTest
    fun testCacheInvalidationAfterCompilerArgModifying(gradleVersion: GradleVersion) {
        project("kotlin2JsIrICProject", gradleVersion) {
            val buildConfig = buildGradleKts.readText()

            fun setLazyInitializationArg(value: Boolean) {
                buildGradleKts.writeText(buildConfig)
                buildGradleKts.appendText(
                    """
                    |
                    |tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileDevelopmentExecutableKotlinJs") {
                    |    kotlinOptions {
                    |        freeCompilerArgs += "-Xir-property-lazy-initialization=$value"
                    |   }
                    |}
                    """.trimMargin()
                )
            }

            fun String.testScriptOutLines() = this.lines().mapNotNull {
                val trimmed = it.removePrefix(">>> TEST OUT: ")
                if (trimmed == it) null else trimmed
            }

            // -Xir-property-lazy-initialization default is true
            build("nodeRun") {
                assertTasksExecuted(":compileDevelopmentExecutableKotlinJs")
                assertEquals(listOf("Hello, Gradle."), output.testScriptOutLines())
            }

            setLazyInitializationArg(false)
            build("nodeRun") {
                assertTasksExecuted(":compileDevelopmentExecutableKotlinJs")
                assertEquals(listOf("TOP LEVEL!", "Hello, Gradle."), output.testScriptOutLines())
            }

            setLazyInitializationArg(true)
            build("nodeRun") {
                assertTasksExecuted(":compileDevelopmentExecutableKotlinJs")
                assertEquals(listOf("Hello, Gradle."), output.testScriptOutLines())
            }
        }
    }

    @DisplayName("Test multiple artifacts in one project")
    @GradleTest
    fun testMultipleArtifacts(gradleVersion: GradleVersion) {
        project("kotlin-js-ir-ic-multiple-artifacts", gradleVersion) {
            build("compileDevelopmentExecutableKotlinJs") {
                val cacheDir = projectPath.resolve("app/build/klib/cache/").toFile()
                val cacheRootDirName = cacheDir.list()?.singleOrNull()
                assertTrue("Lib cache root dir should contain 1 element 'version.hash'") {
                    cacheRootDirName?.startsWith("version.") ?: false
                }
                val cacheRootDir = cacheDir.resolve(cacheRootDirName!!)
                val klibCacheDirs = cacheRootDir.list()
                // 2 for lib.klib + 1 for stdlib + 1 for dom-api-compat + 1 for main
                assertEquals(5, klibCacheDirs?.size, "cache should contain 4 dirs")

                val libKlibCacheDirs = klibCacheDirs?.filter { dir -> dir.startsWith("lib.klib.") }
                assertEquals(2, libKlibCacheDirs?.size, "cache should contain 2 dirs for lib.klib")

                var lib = false
                var libOther = false

                cacheRootDir.listFiles()!!
                    .forEach {
                        it.listFiles()!!
                            .filter { it.isFile }
                            .forEach {
                                val text = it.readText()
                                // cache keeps the js code of compiled module, this substring from that js code
                                if (text.contains("root['kotlin-js-ir-ic-multiple-artifacts-lib']")) {
                                    if (lib) {
                                        error("lib should be only once in cache")
                                    }
                                    lib = true
                                }
                                // cache keeps the js code of compiled module, this substring from that js code
                                if (text.contains("root['kotlin-js-ir-ic-multiple-artifacts-lib-other']")) {
                                    if (libOther) {
                                        error("libOther should be only once in cache")
                                    }
                                    libOther = true
                                }
                            }

                    }

                assertTrue("lib and libOther should be once in cache") {
                    lib && libOther
                }
                assertTasksExecuted(":app:compileDevelopmentExecutableKotlinJs")
            }
        }
    }

    @DisplayName("Test removing unused dependency from klib")
    @GradleTest
    fun testRemoveUnusedDependency(gradleVersion: GradleVersion) {
        project("kotlin-js-ir-ic-remove-unused-dep", gradleVersion) {
            val appBuildGradleKts = subProject("app").buildGradleKts

            val buildGradleKtsWithoutDependency = appBuildGradleKts.readText()
            appBuildGradleKts.appendText(
                """
                |
                |dependencies {
                |    implementation(project(":lib"))
                |}
                |
                """.trimMargin()
            )

            build("compileDevelopmentExecutableKotlinJs") {
                assertTasksExecuted(":app:compileDevelopmentExecutableKotlinJs")
            }

            appBuildGradleKts.writeText(buildGradleKtsWithoutDependency)
            build("compileDevelopmentExecutableKotlinJs") {
                assertTasksExecuted(":app:compileDevelopmentExecutableKotlinJs")
            }
        }
    }
}
