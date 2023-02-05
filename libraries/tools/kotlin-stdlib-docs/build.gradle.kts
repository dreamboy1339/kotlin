import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

plugins {
    base
    id("org.jetbrains.dokka")
}

evaluationDependsOnChildren()

fun pKotlinBig() = project("kotlin_big").ext

val outputDir = file(findProperty("docsBuildDir") as String? ?: "$buildDir/doc")
val outputDirLatest = file("$outputDir/latest")
val outputDirPrevious = file("$outputDir/previous")
val kotlin_root: String by pKotlinBig()
val kotlin_libs: String by pKotlinBig()
val kotlin_native_root = file("$kotlin_root/kotlin-native").absolutePath
val github_revision: String by pKotlinBig()
val localRoot = kotlin_root
val baseUrl = URL("https://github.com/JetBrains/kotlin/tree/$github_revision")
val templatesDir = file("$projectDir/templates").invariantSeparatorsPath

val cleanDocs by tasks.registering(Delete::class) {
    delete(outputDir)
}

tasks.clean {
    dependsOn(cleanDocs)
}

val prepare by tasks.registering {
    dependsOn(":kotlin_big:extractLibs")
}

repositories {
    mavenCentral()
    maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/dokka/dev")
    mavenLocal()
}
val dokka_version: String by project

dependencies {
    dokkaPlugin(project(":plugins:dokka-samples-transformer-plugin"))
    dokkaPlugin(project(":plugins:dokka-stdlib-configuration-plugin"))
    dokkaPlugin(project(":plugins:dokka-version-filter-plugin"))
    dokkaPlugin("org.jetbrains.dokka:versioning-plugin:$dokka_version")
}

fun createStdLibVersionedDocTask(version: String, isLatest: Boolean) =
    tasks.register<DokkaTask>("kotlin-stdlib_" + version + (if (isLatest) "_latest" else "")) {
        dependsOn(prepare)

        val kotlin_stdlib_dir = file("$kotlin_root/libraries/stdlib")

        val stdlibIncludeMd = file("$kotlin_root/libraries/stdlib/src/Module.md")
        val stdlibSamples = file("$kotlin_root/libraries/stdlib/samples/test")

        val suppressedPackages = listOf(
                "kotlin.internal",
                "kotlin.jvm.internal",
                "kotlin.js.internal",
                "kotlin.native.internal",
                "kotlin.jvm.functions",
                "kotlin.coroutines.jvm.internal",
                "kotlin.reflect.jvm.internal"
        )

        var kotlinLanguageVersion = version
        if (version == "1.0")
            kotlinLanguageVersion = "1.1"


        moduleName.set("kotlin-stdlib")
        val moduleDirName = "kotlin-stdlib"
        if (isLatest) {
            outputDirectory.set(outputDirLatest.resolve(moduleDirName))
            with(pluginsMapConfiguration) {
                put("org.jetbrains.dokka.base.DokkaBase"                      , """{ "mergeImplicitExpectActualDeclarations": "true", "templatesDir": "$templatesDir" }""")
                put("org.jetbrains.dokka.kotlinlang.StdLibConfigurationPlugin", """{ "ignoreCommonBuiltIns": "true" }""")
                put("org.jetbrains.dokka.versioning.VersioningPlugin"         , """{ "version": "$version", "olderVersionsDir": "${outputDirPrevious.resolve(moduleDirName).invariantSeparatorsPath}" }""")
            }
        } else {
            outputDirectory.set(outputDirPrevious.resolve(moduleDirName).resolve(version))
            with(pluginsMapConfiguration) {
                put("org.jetbrains.dokka.base.DokkaBase"                      , """{ "mergeImplicitExpectActualDeclarations": "true", "templatesDir": "$templatesDir" }""")
                put("org.jetbrains.dokka.kotlinlang.StdLibConfigurationPlugin", """{ "ignoreCommonBuiltIns": "true" }""")
                put("org.jetbrains.dokka.kotlinlang.VersionFilterPlugin"      , """{ "targetVersion": "$version" }""")
                put("org.jetbrains.dokka.versioning.VersioningPlugin"         , """{ "version": "$version" }""")
            }
        }
        dokkaSourceSets {
            if (version != "1.0" && version != "1.1") { // Common platform since Kotlin 1.2
                register("common") {
                    jdkVersion.set(8)
                    platform.set(Platform.common)
                    noJdkLink.set(true)

                    displayName.set("Common")
                    sourceRoots.from("$kotlin_root/core/builtins/native")
                    sourceRoots.from("$kotlin_root/core/builtins/src/")

                    sourceRoots.from("$kotlin_stdlib_dir/common/src")
                    sourceRoots.from("$kotlin_stdlib_dir/src")
                    sourceRoots.from("$kotlin_stdlib_dir/unsigned/src")
                }
            }

            register("jvm") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)

                displayName.set("JVM")
                if (version != "1.0" && version != "1.1") {
                    dependsOn("common")
                }

                sourceRoots.from("$kotlin_stdlib_dir/jvm/src")

                sourceRoots.from("$kotlin_root/core/reflection.jvm/src")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/jvm/annotations")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/jvm/JvmClassMapping.kt")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/jvm/PurelyImplements.kt")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/Metadata.kt")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/Throws.kt")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/TypeAliases.kt")
                sourceRoots.from("$kotlin_stdlib_dir/jvm/runtime/kotlin/text/TypeAliases.kt")

                // for Kotlin 1.0 and 1.1 hack: Common platform becomes JVM
                if (version == "1.0" || version == "1.1") {
                    sourceRoots.from("$kotlin_root/core/builtins/native")
                    sourceRoots.from("$kotlin_root/core/builtins/src/")

                    sourceRoots.from("$kotlin_stdlib_dir/common/src")
                    sourceRoots.from("$kotlin_stdlib_dir/src")
                    sourceRoots.from("$kotlin_stdlib_dir/unsigned/src")
                }
            }
            if (version != "1.0" && version != "1.1") {
            register("jvm-jdk8") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)

                displayName.set("JVM8")
                dependsOn("jvm")
                dependsOn("common")
                sourceRoots.from("$kotlin_stdlib_dir/jdk8/src")
            }
            register("jvm-jdk7") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)

                displayName.set("JVM7")
                dependsOn("jvm")
                dependsOn("common")
                sourceRoots.from("$kotlin_stdlib_dir/jdk7/src")
            }
            }
            if (version != "1.0") { // JS platform since Kotlin 1.1
                register("js") {
                    jdkVersion.set(8)
                    platform.set(Platform.js)
                    noJdkLink.set(true)

                    displayName.set("JS")
                    if (version != "1.0" && version != "1.1") {
                        dependsOn("common")
                    }

                    sourceRoots.from("$kotlin_stdlib_dir/js/src")
                    sourceRoots.from("$kotlin_stdlib_dir/js-v1/src")

                    // for Kotlin 1.1 hack: Common platform becomes JVM
                    if (version == "1.1") {
                        sourceRoots.from("$kotlin_root/core/builtins/native")
                        sourceRoots.from("$kotlin_root/core/builtins/src/")

                        //sourceRoots.from("$kotlin_stdlib_dir/common/src") // is included  in /js-v1/src folder
                        sourceRoots.from("$kotlin_stdlib_dir/src")
                        sourceRoots.from("$kotlin_stdlib_dir/unsigned/src")
                    }
                    perPackageOption("org.w3c") {
                        reportUndocumented.set(false)
                    }
                    perPackageOption("org.khronos") {
                        reportUndocumented.set(false)
                    }
                }
            }
            if (version != "1.0" && version != "1.1" && version != "1.2") { // Native platform since Kotlin 1.3
                register("native") {
                    jdkVersion.set(8)
                    platform.set(Platform.native)
                    noJdkLink.set(true)

                    displayName.set("Native")
                    dependsOn("common")

                    sourceRoots.from("$kotlin_native_root/Interop/Runtime/src/main/kotlin")
                    sourceRoots.from("$kotlin_native_root/Interop/Runtime/src/native/kotlin")
                    sourceRoots.from("$kotlin_native_root/Interop/JsRuntime/src/main/kotlin")
                    sourceRoots.from("$kotlin_native_root/runtime/src/main/kotlin")
                    sourceRoots.from("$kotlin_stdlib_dir/native-wasm/src")
                    perPackageOption("kotlin.test") {
                        suppress.set(true)
                    }
                }
            }
            configureEach {
                documentedVisibilities.set(setOf(DokkaConfiguration.Visibility.PUBLIC, DokkaConfiguration.Visibility.PROTECTED))
                skipDeprecated.set(false)
                includes.from(stdlibIncludeMd)
                noStdlibLink.set(true)
                languageVersion.set(kotlinLanguageVersion)
                samples.from(stdlibSamples.toString())
                suppressedPackages.forEach { packageName ->
                    perPackageOption(packageName) {
                        suppress.set(true)
                    }
                }
                sourceLinksFromRoot()
            }
        }
    }

fun createKotlinTestVersionedDocTask(version: String, isLatest: Boolean, stdlibDocTask: TaskProvider<DokkaTask>) =
    tasks.register<DokkaTask>("kotlin-test_" + version + (if (isLatest) "_latest" else "")) {
        dependsOn(prepare, stdlibDocTask)

        val kotlinTestIncludeMd = file("$kotlin_root/libraries/kotlin.test/Module.md")

        val kotlinTestCommonClasspath = fileTree("$kotlin_libs/kotlin-test-common")
        val kotlinTestJunitClasspath = fileTree("$kotlin_libs/kotlin-test-junit")
        val kotlinTestJunit5Classpath = fileTree("$kotlin_libs/kotlin-test-junit5")
        val kotlinTestTestngClasspath = fileTree("$kotlin_libs/kotlin-test-testng")
        val kotlinTestJsClasspath = fileTree("$kotlin_libs/kotlin-test-js")
        val kotlinTestJvmClasspath = fileTree("$kotlin_libs/kotlin-test")

        val stdlibPackageList = URL("file:///${stdlibDocTask.get().outputDirectory.get()}/kotlin-stdlib/package-list")
        val kotlinLanguageVersion = version

        moduleName.set("kotlin-test")

        val moduleDirName = "kotlin-test"
        if (isLatest) {
            outputDirectory.set(outputDirLatest.resolve(moduleDirName))
            with(pluginsMapConfiguration) {
                put("org.jetbrains.dokka.base.DokkaBase"                      , """{ "mergeImplicitExpectActualDeclarations": "true", "templatesDir": "$templatesDir" }""")
                put("org.jetbrains.dokka.versioning.VersioningPlugin"         , """{ "version": "$version", "olderVersionsDir": "${outputDirPrevious.resolve(moduleDirName).invariantSeparatorsPath}" }""")
            }
        } else {
            outputDirectory.set(outputDirPrevious.resolve(moduleDirName).resolve(version))
            with(pluginsMapConfiguration) {
                put("org.jetbrains.dokka.base.DokkaBase"                      , """{ "mergeImplicitExpectActualDeclarations": "true", "templatesDir": "$templatesDir" }""")
                put("org.jetbrains.dokka.kotlinlang.VersionFilterPlugin"      , """{ "targetVersion": "$version" }""")
                put("org.jetbrains.dokka.versioning.VersioningPlugin"         , """{ "version": "$version" }""")
            }
        }

        dokkaSourceSets {
            if (version != "1.0" && version != "1.1") { // Common platform since Kotlin 1.2
                register("common") {
                    jdkVersion.set(8)
                    platform.set(Platform.common)
                    classpath.setFrom(kotlinTestCommonClasspath)
                    noJdkLink.set(true)

                    displayName.set("Common")
                    sourceRoots.from("$kotlin_root/libraries/kotlin.test/common/src/main/kotlin")
                    sourceRoots.from("$kotlin_root/libraries/kotlin.test/annotations-common/src/main/kotlin")
                }
            }

            register("jvm") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)
                classpath.setFrom(kotlinTestJvmClasspath)

                displayName.set("JVM")
                if (version != "1.0" && version != "1.1")
                    dependsOn("common")
                sourceRoots.from("$kotlin_root/libraries/kotlin.test/jvm/src/main/kotlin")
                if (version == "1.0" || version == "1.1") {
                    sourceRoots.from("$kotlin_root/libraries/kotlin.test/common/src/main/kotlin")
                    sourceRoots.from("$kotlin_root/libraries/kotlin.test/annotations-common/src/main/kotlin")
                }
            }

            register("jvm-JUnit") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)
                classpath.setFrom(kotlinTestJunitClasspath)

                displayName.set("JUnit")
                if (version != "1.0" && version != "1.1")
                    dependsOn("common")
                dependsOn("jvm")
                sourceRoots.from("$kotlin_root/libraries/kotlin.test/junit/src/main/kotlin")

                externalDocumentationLink {
                    url.set(URL("http://junit.org/junit4/javadoc/latest/"))
                    packageListUrl.set(URL("http://junit.org/junit4/javadoc/latest/package-list"))
                }
            }

            if (version != "1.0" && version != "1.1")
            register("jvm-JUnit5") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)
                classpath.setFrom(kotlinTestJunit5Classpath)

                displayName.set("JUnit5")
                dependsOn("common")
                dependsOn("jvm")
                sourceRoots.from("$kotlin_root/libraries/kotlin.test/junit5/src/main/kotlin")

                externalDocumentationLink {
                    url.set(URL("https://junit.org/junit5/docs/current/api/"))
                    packageListUrl.set(URL("https://junit.org/junit5/docs/current/api/element-list"))
                }
            }

            if (version != "1.0" && version != "1.1")
            register("jvm-TestNG") {
                jdkVersion.set(8)
                platform.set(Platform.jvm)
                classpath.setFrom(kotlinTestTestngClasspath)

                displayName.set("TestNG")
                dependsOn("common")
                dependsOn("jvm")
                sourceRoots.from("$kotlin_root/libraries/kotlin.test/testng/src/main/kotlin")

                // externalDocumentationLink {
                //     url.set(new URL("https://jitpack.io/com/github/cbeust/testng/master/javadoc/"))
                //     packageListUrl.set(new URL("https://jitpack.io/com/github/cbeust/testng/master/javadoc/package-list"))
                // }
            }
            if (version != "1.0") { // JS platform since Kotlin 1.1
                register("js") {
                    platform.set(Platform.js)
                    classpath.setFrom(kotlinTestJsClasspath)
                    noJdkLink.set(true)

                    displayName.set("JS")
                    if (version != "1.1")
                        dependsOn("common")
                    sourceRoots.from("$kotlin_root/libraries/kotlin.test/js/src/main/kotlin")
                    if (version == "1.0" || version == "1.1") {
                        sourceRoots.from("$kotlin_root/libraries/kotlin.test/common/src/main/kotlin")
                        sourceRoots.from("$kotlin_root/libraries/kotlin.test/annotations-common/src/main/kotlin")
                    }
                }
            }
            if (version != "1.0" && version != "1.1" && version != "1.2") { // Native platform since Kotlin 1.3
                register("native") {
                    platform.set(Platform.native)
                    noJdkLink.set(true)

                    displayName.set("Native")
                    dependsOn("common")
                    sourceRoots.from("$kotlin_native_root/runtime/src/main/kotlin/kotlin/test")
                }
            }
            configureEach {
                skipDeprecated.set(false)
                includes.from(kotlinTestIncludeMd)
                languageVersion.set(kotlinLanguageVersion)
                noStdlibLink.set(true)
                sourceLinksFromRoot()
                externalDocumentationLink {
                    url.set(URL("https://kotlinlang.org/api/latest/jvm/stdlib/"))
                    packageListUrl.set(stdlibPackageList)
                }
            }
        }
    }

fun GradleDokkaSourceSetBuilder.perPackageOption(packageNamePrefix: String, action: Action<in GradlePackageOptionsBuilder>) =
    perPackageOption {
        matchingRegex.set(Regex.escape(packageNamePrefix) + "(\$|\\..*)")
        action(this)
    }

fun GradleDokkaSourceSetBuilder.sourceLinksFromRoot() {
    sourceLink {
        localDirectory.set(file(localRoot))
        remoteUrl.set(baseUrl)
        remoteLineSuffix.set("#L")
    }
}

gradle.projectsEvaluated {
    val versions = listOf("1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8")
    val latestVersion = versions.last()

    // builds this version/all versions as historical for the next versions builds
    val buildAllVersions by tasks.registering
    // builds the latest version incorporating all previous historical versions docs
    val buildLatestVersion by tasks.registering

    val latestStdlib = createStdLibVersionedDocTask(latestVersion, true)
    val latestTest = createKotlinTestVersionedDocTask(latestVersion, true, latestStdlib)

    buildLatestVersion.configure { dependsOn(latestStdlib, latestTest) }

    versions.forEach { version ->
        val versionStdlib = createStdLibVersionedDocTask(version, false)
        val versionTest = createKotlinTestVersionedDocTask(version, false, versionStdlib)
        if (version != latestVersion) {
            latestStdlib.configure { dependsOn(versionStdlib) }
            latestTest.configure { dependsOn(versionTest) }
        }
        buildAllVersions.configure { dependsOn(versionStdlib, versionTest) }
    }
}
