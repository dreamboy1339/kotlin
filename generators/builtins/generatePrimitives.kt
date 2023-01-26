/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.jetbrains.kotlin.generators.builtins.PrimitiveType
import org.jetbrains.kotlin.generators.builtins.generateBuiltIns.BUILT_INS_NATIVE_DIR
import java.io.File
import java.io.PrintWriter

private fun String.shift(): String {
    return this.split("\n").joinToString(separator = "\n") { "\t$it" }
}

data class FileDescription(
    val suppress: List<String>, val imports: List<String>, val classes: List<ClassDescription>
) {
    override fun toString(): String {
        return buildString {
            appendLine(File("license/COPYRIGHT_HEADER.txt").readText())
            appendLine()
            appendLine("// Auto-generated file. DO NOT EDIT!")
            appendLine()

            if (suppress.isNotEmpty()) {
                appendLine(suppress.joinToString(separator = ", ", prefix = "@file:Suppress(", postfix = ")") { "\"$it\"" })
                appendLine()
            }

            appendLine("package kotlin")
            appendLine()

            if (imports.isNotEmpty()) {
                appendLine(imports.joinToString(separator = "\n") { "import $it" })
                appendLine()
            }

            appendLine(classes.joinToString(separator = "\n"))
        }
    }
}

data class ClassDescription(
    val doc: String, val annotations: List<String>, val name: String,
    val companionObject: CompanionObjectDescription, val methods: List<MethodDescription>
) {
    override fun toString(): String {
        return buildString {
            appendLine(doc.split("\n").joinToString(separator = "\n", prefix = "/**\n", postfix = "\n */") { " * $it" })
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = "\n") { "@$it" })
            }

            appendLine("public class $name private constructor() : Number(), Comparable<$name> {")
            appendLine(companionObject.toString().shift())
            appendLine(methods.joinToString(separator = "\n\n") { it.toString().shift() })
            appendLine("}")
        }
    }
}

data class CompanionObjectDescription(
    val annotations: List<String>, val properties: List<PropertyDescription>
) {
    override fun toString(): String {
        return buildString {
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = "\n") { "@$it" })
            }

            appendLine("companion object {")
            appendLine(properties.joinToString(separator = "\n\n") { it.toString().shift() })
            appendLine("}")
        }
    }
}

data class MethodSignature(
    val isExternal: Boolean = false, val visibility: String,
    val isOverride: Boolean = false, val isInline: Boolean = false, val isOperator: Boolean = true,
    val name: String, val arg: String?, val returnType: String
) {
    override fun toString(): String {
        return buildString {
            if (isExternal) append("external ")
            append("$visibility ")
            if (isOverride) append("override ")
            if (isInline) append("inline ")
            if (isOperator) append("operator ")
            append("fun $name(${arg ?: ""}): $returnType")
        }
    }
}

data class MethodDescription(
    private var doc: String, var annotations: List<String>, var signature: MethodSignature, var body: String? = null
) {
    fun addDoc(doc: String) {
        this.doc += doc
    }

    fun addAnnotation(annotation: String) {
        annotations += annotation
    }

    override fun toString(): String {
        return buildString {
            appendLine(doc.split("\n").joinToString(separator = "\n", prefix = "/**\n", postfix = "\n */") { " * $it" })
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = "\n") { "@$it" })
            }
            append(signature)
            append(body ?: "") // TODO multi/single line body
        }
    }
}

data class PropertyDescription(
    val doc: String, val annotations: List<String> = emptyList(), val name: String, val type: String, val value: String
) {
    override fun toString(): String {
        return buildString {
            appendLine(doc.split("\n").joinToString(separator = "\n", prefix = "/**\n", postfix = "\n */") { " * $it" })
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = "\n") { "@$it" })
            }
            append("public const val $name: $type = $value")
        }
    }
}

abstract class BaseGenerator {
    private val typeDescriptions: Map<PrimitiveType, String> = mapOf(
        PrimitiveType.DOUBLE to "double-precision 64-bit IEEE 754 floating point number",
        PrimitiveType.FLOAT to "single-precision 32-bit IEEE 754 floating point number",
        PrimitiveType.LONG to "64-bit signed integer",
        PrimitiveType.INT to "32-bit signed integer",
        PrimitiveType.SHORT to "16-bit signed integer",
        PrimitiveType.BYTE to "8-bit signed integer",
        PrimitiveType.CHAR to "16-bit Unicode character"
    )

    private fun primitiveConstants(type: PrimitiveType): List<Any> = when (type) {
        PrimitiveType.INT -> listOf(java.lang.Integer.MIN_VALUE, java.lang.Integer.MAX_VALUE)
        PrimitiveType.BYTE -> listOf(java.lang.Byte.MIN_VALUE, java.lang.Byte.MAX_VALUE)
        PrimitiveType.SHORT -> listOf(java.lang.Short.MIN_VALUE, java.lang.Short.MAX_VALUE)
        PrimitiveType.LONG -> listOf((java.lang.Long.MIN_VALUE + 1).toString() + "L - 1L", java.lang.Long.MAX_VALUE.toString() + "L")
        PrimitiveType.DOUBLE -> listOf(java.lang.Double.MIN_VALUE, java.lang.Double.MAX_VALUE, "1.0/0.0", "-1.0/0.0", "-(0.0/0.0)")
        PrimitiveType.FLOAT -> listOf(java.lang.Float.MIN_VALUE, java.lang.Float.MAX_VALUE, "1.0F/0.0F", "-1.0F/0.0F", "-(0.0F/0.0F)").map { it as? String ?: "${it}F" }
        else -> throw IllegalArgumentException("type: $type")
    }

    fun generate(): String {
        return FileDescription(
            suppress = emptyList(),
            imports = emptyList(),
            classes = generateClasses()
        ).toString()
    }

    private fun generateClasses(): List<ClassDescription> {
        return buildList {
            for (kind in PrimitiveType.onlyNumeric) {
                val className = kind.capitalized
                val doc = generateDoc(kind)

                val properties = buildList {
                    if (kind == PrimitiveType.FLOAT || kind == PrimitiveType.DOUBLE) {
                        val (minValue, maxValue, posInf, negInf, nan) = primitiveConstants(kind)
                        this += PropertyDescription(
                            doc = "A constant holding the smallest *positive* nonzero value of $className.",
                            name = "MIN_VALUE",
                            type = className,
                            value = minValue.toString()
                        )

                        this += PropertyDescription(
                            doc = "A constant holding the largest positive finite value of $className.",
                            name = "MAX_VALUE",
                            type = className,
                            value = maxValue.toString()
                        )

                        this += PropertyDescription(
                            doc = "A constant holding the positive infinity value of $className.",
                            name = "POSITIVE_INFINITY",
                            type = className,
                            value = posInf.toString()
                        )

                        this += PropertyDescription(
                            doc = "A constant holding the negative infinity value of $className.",
                            name = "NEGATIVE_INFINITY",
                            type = className,
                            value = negInf.toString()
                        )

                        this += PropertyDescription(
                            doc = "A constant holding the \"not a number\" value of $className.",
                            name = "NaN",
                            type = className,
                            value = nan.toString()
                        )
                    }

                    if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG || kind == PrimitiveType.SHORT || kind == PrimitiveType.BYTE) {
                        val (minValue, maxValue) = primitiveConstants(kind)
                        this += PropertyDescription(
                            doc = "A constant holding the minimum value an instance of $className can have.",
                            name = "MIN_VALUE",
                            type = className,
                            value = minValue.toString()
                        )

                        this += PropertyDescription(
                            doc = "A constant holding the maximum value an instance of $className can have.",
                            name = "MAX_VALUE",
                            type = className,
                            value = maxValue.toString()
                        )
                    }

                    val sizeSince = if (kind.isFloatingPoint) "1.4" else "1.3"
                    this += PropertyDescription(
                        doc = "The number of bytes used to represent an instance of $className in a binary form.",
                        listOf("SinceKotlin(\"$sizeSince\")"),
                        name = "SIZE_BYTES",
                        type = "Int",
                        value = kind.byteSize.toString()
                    )

                    this += PropertyDescription(
                        doc = "The number of bits used to represent an instance of $className in a binary form.",
                        listOf("SinceKotlin(\"$sizeSince\")"),
                        name = "SIZE_BITS",
                        type = "Int",
                        value = kind.bitSize.toString()
                    )
                }

                val methods = buildList {
                    this.addAll(generateCompareTo(kind))
                }

//                generateBinaryOperators(kind)
//                generateUnaryOperators(kind)
//                generateRangeTo(kind)
//                generateRangeUntil(kind)

//            if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG) {
//                generateBitShiftOperators(kind)
//            }
//            if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG /* || kind == PrimitiveType.BYTE || kind == PrimitiveType.SHORT */) {
//                generateBitwiseOperators(className, since = if (kind == PrimitiveType.BYTE || kind == PrimitiveType.SHORT) "1.1" else null)
//            }
//
//            generateConversions(kind)
//            generateEquals()
//            generateToString()

                this += ClassDescription(
                    doc,
                    annotations = emptyList(),
                    name = className,
                    companionObject = CompanionObjectDescription(
                        annotations = emptyList(),
                        properties = properties
                    ),
                    methods = methods
                )
            }
        }
    }

    private fun generateDoc(kind: PrimitiveType): String {
        return "Represents a ${typeDescriptions[kind]}.\n" +
                "On the JVM, non-nullable values of this type are represented as values of the primitive type `${kind.name.lowercase()}`."
    }

    fun generateCompareTo(kind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val doc =
                    "Compares this value with the specified value for order. \n" +
                            "Returns zero if this value is equal to the specified other value, a negative number if it's less than other, \n" +
                            "or a positive number if it's greater than other."

                val signature = MethodSignature(
                    isExternal = false,
                    visibility = "public",
                    isOverride = otherKind == kind,
                    isOperator = true,
                    name = "compareTo",
                    arg = "other: ${otherKind.capitalized}",
                    returnType = "Int"
                )

                this += MethodDescription(
                    doc = doc,
                    annotations = listOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = signature
                )
            }
        }
    }

    fun generateBinaryOperators(kind: PrimitiveType) {}
    fun generateUnaryOperators(kind: PrimitiveType) {}
    fun generateRangeTo(kind: PrimitiveType) {}
    fun generateRangeUntil(kind: PrimitiveType) {}

    abstract fun modifyGeneratedCompareTo(kind: PrimitiveType, otherType: PrimitiveType)
}

class JvmGenerator : BaseGenerator() {
    override fun modifyGeneratedCompareTo(kind: PrimitiveType, otherType: PrimitiveType) {
        TODO("Not yet implemented")
    }
}

class NativeGenerator : BaseGenerator() {
    override fun modifyGeneratedCompareTo(kind: PrimitiveType, otherType: PrimitiveType) {
        TODO("Not yet implemented")
    }
}

fun main() {
    val primitivesFile = File(BUILT_INS_NATIVE_DIR, "kotlin/Primitives_new.kt")
    primitivesFile.parentFile?.mkdirs()
    PrintWriter(primitivesFile).use {
        it.print(JvmGenerator().generate())
    }
}
