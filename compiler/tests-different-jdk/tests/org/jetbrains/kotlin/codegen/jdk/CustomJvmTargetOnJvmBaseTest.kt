/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.jdk

import org.jetbrains.kotlin.codegen.jdk.RunOnlyJdk6Test
import org.jetbrains.kotlin.test.runners.codegen.BlackBoxCodegenTestGenerated
import org.jetbrains.kotlin.test.runners.codegen.BlackBoxInlineCodegenTestGenerated
import org.jetbrains.kotlin.test.runners.codegen.CompileKotlinAgainstInlineKotlinTestGenerated
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.platform.runner.JUnitPlatform
import org.junit.platform.suite.api.IncludeClassNamePatterns
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.UseTechnicalNames
import org.junit.runner.RunWith

/*
 * NB: ALL NECESSARY FLAGS ARE PASSED THROUGH Gradle
 */

@SelectClasses(
    BlackBoxCodegenTestGenerated::class,
    BlackBoxInlineCodegenTestGenerated::class,
    CompileKotlinAgainstInlineKotlinTestGenerated::class
)
@IncludeClassNamePatterns(".*Test.*Generated")
@UseTechnicalNames
abstract class CustomJvmTargetOnJvmBaseTest

@RunOnlyJdk6Test
@Execution(ExecutionMode.SAME_THREAD)
@RunWith(JUnitPlatformRunnerForJdk6::class)
class JvmTarget6OnJvm6 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget8OnJvm8 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget6OnJvm11 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget8OnJvm11 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget11OnJvm11 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget6OnJvmLast : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget8OnJvmLast : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTargetLastOnJvmLast : CustomJvmTargetOnJvmBaseTest()

//TODO: delete old tasks
@RunWith(JUnitPlatform::class)
class JvmTarget6OnJvm9 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget8OnJvm9 : CustomJvmTargetOnJvmBaseTest()

@RunWith(JUnitPlatform::class)
class JvmTarget9OnJvm9 : CustomJvmTargetOnJvmBaseTest()

