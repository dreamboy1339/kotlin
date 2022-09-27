// DO NOT EDIT MANUALLY!
// Generated by org/jetbrains/kotlin/generators/arguments/GenerateGradleOptions.kt
// To regenerate run 'generateGradleOptions' task
@file:Suppress("RemoveRedundantQualifierName", "Deprecation", "DuplicatedCode")

package org.jetbrains.kotlin.gradle.dsl

import org.gradle.api.Project

interface CompilerCommonOptions : org.jetbrains.kotlin.gradle.dsl.CompilerCommonToolOptions {

    /**
     * Allow using declarations only from the specified version of bundled libraries
     * Possible values: "1.3 (deprecated)", "1.4 (deprecated)", "1.5", "1.6", "1.7", "1.8", "1.9 (experimental)"
     * Default value: null
     */
    @get:org.gradle.api.tasks.Optional
    @get:org.gradle.api.tasks.Input
    val apiVersion: org.gradle.api.provider.Property<org.jetbrains.kotlin.gradle.dsl.KotlinVersion>

    /**
     * Provide source compatibility with the specified version of Kotlin
     * Possible values: "1.3 (deprecated)", "1.4 (deprecated)", "1.5", "1.6", "1.7", "1.8", "1.9 (experimental)"
     * Default value: null
     */
    @get:org.gradle.api.tasks.Optional
    @get:org.gradle.api.tasks.Input
    val languageVersion: org.gradle.api.provider.Property<org.jetbrains.kotlin.gradle.dsl.KotlinVersion>

    /**
     * Compile using experimental K2. K2 is a new compiler pipeline, no compatibility guarantees are yet provided
     * Default value: false
     */
    @get:org.gradle.api.tasks.Input
    val useK2: org.gradle.api.provider.Property<kotlin.Boolean>
}
